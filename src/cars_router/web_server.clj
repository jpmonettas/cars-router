(ns cars-router.web-server
  (:require [com.stuartsierra.component :as comp]
            [org.httpkit.server :as httpkit-server]
            [taoensso.timbre :as l]
            [compojure.api.sweet :refer :all]
            [ring.util.http-response :refer :all]
            [schema.core :as sch]))


(defrecord WebServer [server handler call-backs opts])

(defn unmanaged-exceptions-handler [e]
  (let [ex-detail {:message (.getMessage e)
                   :stack-trace (map str (.getStackTrace e))}]
    (l/error "Unmanaged exception"
             (.getMessage e)
             (clojure.stacktrace/print-stack-trace e))
    (internal-server-error ex-detail)))

(sch/defschema tag
  {:id sch/Str
   :owner-name sch/Str
   :intervals [{:from-hour sch/Int
               :to-hour sch/Int}]})

(def api-routes
  (api
   {:coercion :schema
    :exceptions {:handlers {:compojure.api.exception/default unmanaged-exceptions-handler}}
    :api {:invalid-routes-fn (constantly nil)}
    :swagger {:spec "/swagger.json"
              :ui "/api-docs"
              :data {:info {:version "1.0.0"
                            :title "Car router api"
                            :description "Communicate with cars in the field"}}}}
   
   (context "/api" []
     (GET "/cars/:car-id/tags" req
       :return [tag]
       (let [list-tags (-> req :call-backs deref :list-tags)]
         ))

     (POST "/cars/:car-id/tags" req
       :body [body tag]
       :return sch/Bool
       (let [upsert-tag (-> req :call-backs deref :upsert-tag)]
         ))

     (DELETE "/cars/:car-id/tags/:tag-id" [id :as req]
       :return sch/Bool
       (let [rm-tag (-> req :call-backs deref :rm-tag)]
         ))))) 

(defn wrap-callbacks [call-backs next-handler]
  (fn [req]
    (next-handler (assoc req :call-backs call-backs))))

(extend-type WebServer

  comp/Lifecycle

  (start [this]
    (let [call-backs (atom {})
          handler (wrap-callbacks call-backs #'api-routes)
          http-server (when (-> this :opts :start-server?)
                        (httpkit-server/run-server handler
                                                   {:port 1234}))]
      (l/info "[WebServer]  component started")
     (assoc this
            :call-backs call-backs
            :handler handler
            :server http-server)))
  
  (stop [this]
    (when-let [stop-fn (:server this)]
     (stop-fn))
    (l/info "[WebServer] component stopped")
    (assoc this
           :server nil
           :call-backs nil?)))

(defn handler [web-server-cmp]
  (:handler web-server-cmp))

(defn make-web-server [opts]
  (map->WebServer {:opts opts}))


