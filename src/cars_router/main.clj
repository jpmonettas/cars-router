(ns cars-router.main
  (:require [cars-router.web-server :refer [make-web-server]]
            [clj-mqtt-component.core :refer [make-mqtt]]
            [clojure.tools.nrepl.server :as nrepl]
            [com.stuartsierra.component :as comp]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [taoensso.timbre :as l]
            [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]])
  (:gen-class))

(def system nil)

(defn create-system [opts]
  (comp/system-map
   :web-server (comp/using (make-web-server {:start-server? true})
                           [:mqtt])
   :mqtt (make-mqtt {:url "tcp://127.0.0.1:1883"
                     :client-id "cars-router"
                     :keep-alive-interval 60
                     :on-connection-lost (fn [e] (l/warn "MQTT connection lost." e))
                     :on-connect-complete (fn [client reconnect? uri]
                                            (l/info "MQTT connection created to " uri " with id cars-router and reconnect set to " reconnect?))})))

(defn start-system [opts]
  (alter-var-root #'system (fn [s] (comp/start (create-system opts)))))

(defn stop-system []
  (alter-var-root #'system (fn [s]
                             (when s (comp/stop s)))))

(defn -main
  [& args]

  (l/merge-config! {:log-level :debug 
                    :appenders {:rotor (rotor-appender {:path "cars-router.log"})}})

  (nrepl/start-server :handler cider-nrepl-handler
                      :port 7778
                      :bind "0.0.0.0")
  (l/info "Nrepl server started.")

  (start-system {})
  
  (l/info "System started")
  
  (Thread/setDefaultUncaughtExceptionHandler
   (reify
     Thread$UncaughtExceptionHandler
     (uncaughtException [this thread throwable]
       (l/error (format "Uncaught exception %s on thread %s" throwable thread) throwable)
       (.printStackTrace throwable)))))
