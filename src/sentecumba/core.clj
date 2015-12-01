(ns sentecumba.core
  (:require [clojure.edn :as edn]
            [catacumba.core :as ct]
            [catacumba.impl.context :as ctx]
            [catacumba.impl.handlers :as hs]
            [catacumba.handlers.misc :as misc]
            [catacumba.handlers.parse :as parsing]
            [catacumba.http :as http]
            [catacumba.helpers :as hp]
            [catacumba.impl.websocket :as cws]
            [taoensso.sente.interfaces :as i]
            [taoensso.sente :as sente]
            [ring.middleware.params :as p]
            [ring.middleware.keyword-params :as kp]
            [clojure.core.async :refer [<! >! chan put! timeout go go-loop close! tap untap mult] :as async]
            [clojure.pprint :refer [pprint]])
  (:import (ratpack.http TypedData)
           (ratpack.handling Context)
           catacumba.impl.context.DefaultContext
           ratpack.handling.Handler
           ratpack.exec.Blocking
           (catacumba.websocket WebSockets WebSocketHandler WebSocket WebSocketMessage)
           (ratpack.func Action)))


(defmethod parsing/parse-body :application/edn
  [^Context ctx ^TypedData body]
  (let [^String data (slurp body)]
    (edn/read-string data)))

(defn my-error-handler
  [context error]
  (http/internal-server-error (.getMessage error)))

(defn- basic-context
  {:internal true :no-doc true}
  [^Context ctx]
  (ctx/context {:catacumba/context  ctx
                :catacumba/request  (.getRequest ctx)
                :catacumba/response (.getResponse ctx)}))

(defmethod hs/adapter :sente/ring
  [handler]
  (reify Handler
    (^void handle [_ ^Context ctx]
      (let [promise (hp/blocking
                      (let [request (assoc (kp/keyword-params-request (p/params-request (hs/build-ring-request (.getRequest ctx)))) :catacumba/context (basic-context ctx))]
                        (handler request)))]
        (hp/then promise (fn [response]
                           (when (satisfies? hs/IHandlerResponse response)
                             (let [context (basic-context ctx)]
                               (hs/-handle-response response context)))))))))

(deftype WebSocketSession [callback-map ^:volatile-mutable web-socket]
  WebSocketHandler
  (^void onOpen [this ^WebSocket ws]
    (let [on-open (:on-open callback-map)]
      (set! web-socket ws)
      (when on-open (on-open this))))

  (^void onMessage [this ^WebSocketMessage msg ^Action callback]
    (let [data (.getData msg)
          on-msg (:on-msg callback-map)]
      (when on-msg (on-msg this data))
      (.execute callback nil)))

  (^void onClose [this]
    (let [on-close (:on-close callback-map)]
      (when on-close (on-close this {}))))

  i/IAsyncNetworkChannel
  (open? [ch] (println "open?" (.isOpen web-socket)) (.isOpen web-socket))
  (close! [ch] (println "close!") (.close web-socket))
  (send!* [ch msg close-after-send?]
    (println msg)
    (let [r (.send web-socket msg)]
      #_(when (boolean close-after-send?) (.close web-socket))
      true)))

(defn websocket
  [context callback-map]
  (try
    (let [wss (WebSocketSession. callback-map nil)]
      (WebSockets/websocket ^Context (:catacumba/context context) wss)
      wss)
    (catch Exception e
      (println e)
      (throw e))))

(deftype CatacumbaAsyncNetworkChannelAdaptor []
  i/IAsyncNetworkChannelAdapter
  (ring-req->net-ch-resp [net-ch-adapter ring-req callbacks-map]
    (let [ctx (:catacumba/context ring-req)
          wss (websocket ctx callbacks-map)]
      wss)))

(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! (CatacumbaAsyncNetworkChannelAdaptor.) {})]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)                                     ; ChannelSocket's receive channel
  (def chsk-send! send-fn)                                  ; ChannelSocket's send API fn
  (def connected-uids connected-uids)                       ; Watchable, read-only atom
  )

(defn event-handler
  [event]
  (when (= :doink/flibbit (:id event)) (pprint event)))

(sente/start-chsk-router! ch-chsk #'event-handler)

(defn ajax-get-or-ws-handshake
  {:handler-type :sente/ring}
  [request]
  (let [resp (ring-ajax-get-or-ws-handshake request)]
    nil))

(defn ajax-post
  {:handler-type :sente/ring}
  [request]
  (ring-ajax-post request))

(def routes
  (ct/routes
    [[:any (misc/autoreloader)]
     [:error #'my-error-handler]
     [:assets "" {:dir     "target"
                  :indexes ["index.html"]}]
     [:prefix "chsk"
      [:by-method
       {:get  #'ajax-get-or-ws-handshake
        :post #'ajax-post}]]]))

(comment
  ;;; TODO - put in -main
  (def srvr (ct/run-server routes {:basedir "/home/dave/data/projects/sandbox/sentecumba"
                                   :debug   true
                                   :port    5050}))
  (chsk-send! :sente/all-users-without-uid [:foo/bar "Test"])
  (.stop srvr)
  )