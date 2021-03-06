;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.notifications
  "A websocket based notifications mechanism."
  (:require
   [app.common.exceptions :as ex]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.redis :as redis]
   [app.util.time :as dt]
   [app.util.transit :as t]
   [clojure.core.async :as a :refer [>! <!]]
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [ring.adapter.jetty9 :as jetty]))

(defmacro go-try
  [& body]
  `(a/go
     (try
       ~@body
       (catch Throwable e# e#))))

(defmacro <?
  [ch]
  `(let [r# (a/<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))

(defmacro thread-try
  [& body]
  `(a/thread
     (try
       ~@body
       (catch Throwable e#
         e#))))

(defn- publish
  [channel message]
  (go-try
   (let [message (t/encode-str message)]
     (<? (redis/run :publish {:channel (str channel)
                              :message message})))))

(def ^:private
  sql:retrieve-presence
  "select * from presence
    where file_id=?
      and (clock_timestamp() - updated_at) < '5 min'::interval")

(defn- retrieve-presence
  [file-id]
  (thread-try
   (let [rows (db/exec! db/pool [sql:retrieve-presence file-id])]
     (mapv (juxt :session-id :profile-id) rows))))

(def ^:private
  sql:update-presence
  "insert into presence (file_id, session_id, profile_id, updated_at)
   values (?, ?, ?, clock_timestamp())
       on conflict (file_id, session_id, profile_id)
       do update set updated_at=clock_timestamp()")

(defn- update-presence
  [file-id session-id profile-id]
  (thread-try
   (let [now (dt/now)
         sql [sql:update-presence file-id session-id profile-id]]
     (db/exec-one! db/pool sql))))

(defn- delete-presence
  [file-id session-id profile-id]
  (thread-try
   (db/delete! db/pool :presence {:file-id file-id
                                  :profile-id profile-id
                                  :session-id session-id})))

;; --- WebSocket Messages Handling

(defmulti handle-message
  (fn [ws message] (:type message)))

;; TODO: check permissions for join a file-id channel (probably using
;; single use token for avoid explicit database query).

(defmethod handle-message :connect
  [{:keys [file-id profile-id session-id output] :as ws} message]
  (log/debugf "profile '%s' is connected to file '%s'" profile-id file-id)
  (go-try
   (<? (update-presence file-id session-id profile-id))
   (let [members (<? (retrieve-presence file-id))]
     (<? (publish file-id {:type :presence :sessions members})))))

(defmethod handle-message :disconnect
  [{:keys [profile-id file-id session-id] :as ws} message]
  (log/debugf "profile '%s' is disconnected from '%s'" profile-id file-id)
  (go-try
   (<? (delete-presence file-id session-id profile-id))
   (let [members (<? (retrieve-presence file-id))]
     (<? (publish file-id {:type :presence :sessions members})))))

(defmethod handle-message :keepalive
  [{:keys [profile-id file-id session-id] :as ws} message]
  (update-presence file-id session-id profile-id))

(defmethod handle-message :pointer-update
  [{:keys [profile-id file-id session-id] :as ws} message]
  (let [message (assoc message
                       :profile-id profile-id
                       :session-id session-id)]
    (publish file-id message)))

(defmethod handle-message :default
  [ws message]
  (a/go
    (log/warnf "received unexpected message: " message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WebSocket Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- forward-message
  [{:keys [out session-id profile-id] :as ws} message]
  (go-try
   (when-not (= (:session-id message) session-id)
     (>! out message))))

(defn start-loop!
  [{:keys [in out sub] :as ws}]
  (go-try
   (loop []
     (let [timeout (a/timeout 30000)
           [val port] (a/alts! [in sub timeout])]
       ;; (prn "alts" val "from" (cond (= port in)  "input"
       ;;                              (= port sub) "redis"
       ;;                              :else "timeout"))

       (cond
         ;; Process message coming from connected client
         (and (= port in) (not (nil? val)))
         (do
           (<? (handle-message ws val))
           (recur))

         ;; Forward message to the websocket
         (and (= port sub) (not (nil? val)))
         (do
           (<? (forward-message ws val))
           (recur))

         ;; Timeout channel signaling
         (= port timeout)
         (do
           (>! out {:type :ping})
           (recur))

         :else
         nil)))))

(defn disconnect!
  [conn]
  (let [session (.getSession conn)]
    (when session
      (.disconnect session))))

(defn- on-subscribed
  [{:keys [conn] :as ws}]
  (a/go
    (try
      (<? (handle-message ws {:type :connect}))
      (<? (start-loop! ws))
      (<? (handle-message ws {:type :disconnect}))
      (catch Throwable err
        (log/errorf err "Unexpected exception on websocket handler.")
        (disconnect! conn)))))

(defrecord WebSocket [conn in out sub])

(defonce metrics-active-connections
  (mtx/gauge {:id "notificatons__active_connections"
              :help "Active connections to the notifications service."}))

(defonce metrics-message-counter
  (mtx/counter {:id "notificatons__messages_counter"
                :help "A total number of messages handled by the notifications service."}))

(defn websocket
  [{:keys [file-id profile-id] :as params}]
  (let [in  (a/chan 32)
        out (a/chan 32)]
    {:on-connect
     (fn [conn]
       (metrics-active-connections :inc)
       (let [xf  (map t/decode-str)
             sub (redis/subscribe (str file-id) xf)
             ws  (WebSocket. conn in out sub nil params)]

         ;; RCV LOOP
         (a/go-loop []
           (let [val (a/<! out)]
             (when-not (nil? val)
               (jetty/send! conn (t/encode-str val))
               (recur))))

         (a/go
           (a/<! (on-subscribed ws))
           (a/close! sub))))

     :on-error
     (fn [conn e]
       (a/close! out)
       (a/close! in))

     :on-close
     (fn [conn status-code reason]
       (metrics-active-connections :dec)
       (a/close! out)
       (a/close! in))

     :on-text
     (fn [ws message]
       (metrics-message-counter :inc)
       (let [message (t/decode-str message)]
         (a/>!! in message)))

     :on-bytes
     (constantly nil)}))


