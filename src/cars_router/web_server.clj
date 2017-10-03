(ns cars-router.web-server
  (:require [com.stuartsierra.component :as comp]
            [aleph.http :as http-server]
            [ring.util.http-response :refer :all]
            [compojure.api.sweet :refer :all]
            [schema.core :as sch]
            [taoensso.timbre :as l]
            [manifold.stream :as mstream]
            [clj-mqtt-component.core :as mqtt]
            [clojure.string :as str]))

(defrecord WebServer [server handler mqtt opts])

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
     (GET "/cars/:car-id/tags" [car-id :as req]
       :return [tag]
       (let [{:keys [status val error]} (mqtt/publish-and-wait-response (:mqtt-cmp req)
                                                                        (str car-id "/method-call")
                                                                        [:list-tags])]
         (if (= status "ok")
           (ok val)
           (bad-gateway error))))

     (POST "/cars/:car-id/tags" [car-id :as req]
       :body [body tag]
       :return sch/Bool
       (let [{:keys [status val error]} (mqtt/publish-and-wait-response (:mqtt-cmp req)
                                                                        (str car-id "/method-call")
                                                                        [:upsert-tag body])]
         (if (= status "ok")
           (ok val)
           (bad-gateway error))))

     (POST "/cars/:car-id/lock-doors" [car-id :as req]
       :return sch/Bool
       (let [{:keys [status val error]} (mqtt/publish-and-wait-response (:mqtt-cmp req)
                                                                        (str car-id "/method-call")
                                                                        [:lock-doors])]
         (if (= status "ok")
           (ok val)
           (bad-gateway error))))

     (POST "/cars/:car-id/authorize-and-unlock-doors" [car-id :as req]
       :return sch/Bool
       (let [{:keys [status val error]} (mqtt/publish-and-wait-response (:mqtt-cmp req)
                                                                        (str car-id "/method-call")
                                                                        [:authorize-and-unlock-doors])]
         (if (= status "ok")
           (ok val)
           (bad-gateway error))))

     (DELETE "/cars/:car-id/tags/:tag-id" [car-id tag-id :as req]
       :return sch/Bool
       (let [{:keys [status val error]} (mqtt/publish-and-wait-response (:mqtt-cmp req)
                                                                        (str car-id "/method-call")
                                                                        [:rm-tag tag-id])]
         (if (= status "ok")
           (ok val)
           (bad-gateway error)))))))

(defn wrap-mqtt [mqtt-cmp next-handler]
  (fn [req]
    (next-handler (assoc req :mqtt-cmp mqtt-cmp))))

(extend-type WebServer

  comp/Lifecycle

  (start [this]
    (let [handler (wrap-mqtt (:mqtt this) #'api-routes)
          http-server (when (-> this :opts :start-server?)
                        (http-server/start-server handler {:port 1234}))]
      (l/info "[WebServer]  component started")
      (assoc this
             :handler handler
             :server http-server)))
  
  (stop [this]
    (when-let [server (:server this)]
      (.close server))
    (l/info "[WebServer] component stopped")
    (assoc this
           :server nil)))


(defn handler [web-server-cmp]
  (:handler web-server-cmp))

(defn make-web-server [opts]
  (map->WebServer {:opts opts}))


