(defproject comrade/app "0.1.0"
  :description "Combined Site and API framework for Ring/Compojure/Buddy apps"
  :url "https://github.com/gandalfhz/comrade"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.4.0"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-json "0.4.0"]
                 [buddy/buddy-auth "0.9.0"]
                 [cheshire "5.5.0"]]
  :plugins [[lein-ring "0.9.7"]]
  :profiles {:dev {:dependencies [[ring/ring-mock "0.3.0"]]}})

