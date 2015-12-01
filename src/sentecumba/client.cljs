(ns sentecumba.client
  (:require-macros
    [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require
    ;; <other stuff>
    [cljs.core.async :as async :refer (<! >! put! chan)]
    [taoensso.sente  :as sente :refer (cb-success?)] ; <--- Add this
    [cljs-http.client :as http]
    ))

(enable-console-print!)

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket! "/chsk" ; Note the same path as before
                                  {:type :ws ; e/o #{:auto :ajax :ws}
                                   })]
     (def chsk       chsk)
     (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
     (def chsk-send! send-fn) ; ChannelSocket's send API fn
     (def chsk-state state)   ; Watchable, read-only atom
     )

(go-loop []
         (println (<! ch-chsk))
         (recur))

(js/setTimeout #(chsk-send! [:doink/flibbit {:stuff "Shiz"}]) 500)
(js/setTimeout #(chsk-send! [:doink/flibbit {:stuff "Snarf"}]) 1000)