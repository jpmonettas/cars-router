(defproject cars-router "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                 [org.clojure/tools.nrepl "0.2.12"]
                 [cider/cider-nrepl "0.15.0"]
                 [http-kit "2.2.0"]
                 [com.stuartsierra/component "0.3.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [org.clojure/tools.namespace "0.3.0-alpha4"]
                 [org.clojure/core.async "0.3.443"]
                 [metosin/compojure-api "2.0.0-alpha7"]
                 [ring/ring-mock "0.3.1"]]
  :main ^:skip-aot cars-router.main
  :target-path "target/%s"
  :repl-options {:init-ns user}
  :profiles {:uberjar {:aot :all}
             :dev {:source-paths ["dev"]
                   :dependencies [[com.stuartsierra/component.repl "0.2.0"]]}})
