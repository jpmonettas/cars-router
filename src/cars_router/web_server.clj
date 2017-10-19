(ns cars-router.web-server
  (:require [com.stuartsierra.component :as comp]
            [aleph.http :as http-server]
            [ring.util.http-response :refer :all]
            [compojure.api.sweet :refer :all]
            [schema.core :as sch]
            [taoensso.timbre :as l]
            [manifold.stream :as mstream]
            [clj-mqtt-component.core :as mqtt]
            [clojure.string :as str]
            [amalloy.ring-buffer :refer [ring-buffer]]))

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

     (GET "/cars/:car-id/authorizations" [car-id :as req]
       :return [{:tag-id sch/Str
                 :timestamp sch/Num}]
       (ok (get-in @(:cars-state req) [car-id :authorizations])))

     (GET "/cars/:car-id/positions" [car-id :as req]
       :return [{:latitude sch/Num
                 :longitude sch/Num
                 :timestamp sch/Num}]
       (ok (get-in @(:cars-state req) [car-id :positions])))

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

(defn wrap-stuff [mqtt-cmp cars-state next-handler]
  (fn [req]
    (next-handler (assoc req
                         :mqtt-cmp mqtt-cmp
                         :cars-state cars-state))))

(extend-type WebServer

  comp/Lifecycle

  (start [this]
    (let [cars-state (atom {})
          handler (wrap-stuff (:mqtt this) cars-state #'api-routes)
          http-server (when (-> this :opts :start-server?)
                        (http-server/start-server handler {:port 1234}))]
      (l/info "[WebServer]  component started")
      
      ;; TODO: this doesn't belong here but just as a experiment
      
      (mqtt/subscribe (:mqtt this) "+/position" (fn [topic position]
                                                  (let [[_ car-id] (re-find #"(.+)/position" topic)]
                                                    (l/debug "Got position report from " car-id " " position)
                                                    (swap! cars-state update-in [car-id :positions] (fnil #(conj % (assoc position :timestamp (System/currentTimeMillis)))
                                                                                                          (ring-buffer 1000))))))

      (mqtt/subscribe (:mqtt this) "+/authorization" (fn [topic tag-id]
                                                       (let [[_ car-id] (re-find #"(.+)/authorization" topic)]
                                                         (l/debug "Got auth report from " car-id " with tag id " tag-id)
                                                         (swap! cars-state update-in [car-id :authorizations] (fnil #(conj % {:tag-id tag-id
                                                                                                                              :timestamp (System/currentTimeMillis)})
                                                                                                                    (ring-buffer 100))))))
      
      (assoc this
             :handler handler
             :server http-server
             :cars-state cars-state)))
  
  (stop [this]
    (when-let [server (:server this)]
      (.close server))
    (l/info "[WebServer] component stopped")
    (assoc this
           :cars-state nil
           :server nil)))


(defn handler [web-server-cmp]
  (:handler web-server-cmp))

(defn make-web-server [opts]
  (map->WebServer {:opts opts}))


