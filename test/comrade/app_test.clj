;; Copyright 2016 Gandalf Hernandez
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns comrade.app-test
  (:require [comrade.app :as comrade-app]
            [clojure.test :refer :all]
            [clojure.string :as s]
            [compojure.core :refer [defroutes routes context GET POST PUT DELETE HEAD]]
            [compojure.coercions :refer [as-int]]
            [compojure.route :as route]
            [ring.util.response :as ring-response]
            [ring.mock.request :as mock]
            [cheshire.core :as json]))

(defn- authenticate [username password]
  (cond
    (and (= username "user-admin-a") (= password "pass-a"))
    {:username username :other-data "something-else-for-a" :user true :admin true}
    (and (= username "admin-b") (= password "pass-b"))
    {:username username :other-data "something-else-for-b" :admin true}
    (and (= username "user-c") (= password "pass-c"))
    {:username username :other-data "something-else-for-c" :user true}
    (and (= username "no-rights-d") (= password "pass-d"))
    {:username username :other-data "something-else-for-d"}))

(defn- api-access-denied [request tried-admin suggested-code]
  (-> (ring-response/response
        {:error (if tried-admin "Admin API access denied" "User API access denied")})
      (ring-response/status suggested-code)))

(defn- site-access-denied [request tried-admin suggested-code]
  (-> (ring-response/response
        (if tried-admin "Admin site access denied" "User site access denied"))
      (ring-response/status suggested-code)))

;; REST routes
(defroutes admin-api-routes
  (GET "/" [:as {user-data :identity}]
       (ring-response/response {:username (:username user-data)}))
  ;; For some reason, at least for testing, head has to be before get.
  (HEAD "/test/:id" [id :as {user-data :identity}]
       (if (= id "1")
        (ring-response/response nil)
         (-> (ring-response/response nil)
             (ring-response/status 404))))
  (GET "/test/:id" [id :as {user-data :identity}]
       (ring-response/response {:username (:username user-data) :admin-get-id id}))
  (POST "/test/:id" [id :<< as-int :as {user-data :identity} :as {body :body}]
       (ring-response/response {:username (:username user-data) :admin-post-id id :post body}))
  (PUT "/test/:id" [id :as {user-data :identity} :as {body :body}]
       (ring-response/response {:username (:username user-data) :admin-put-id id :put body}))
  (DELETE "/test/:id" [id :as {user-data :identity}]
       (ring-response/response {:username (:username user-data) :admin-delete-id id}))
  (route/not-found {:body {:error "Admin API not found"}}))

(defroutes user-api-routes
  (GET "/" [:as {user-data :identity}]
       (ring-response/response {:username (:username user-data)}))
  ;; For some reason, at least for testing, head has to be before get.
  (HEAD "/test/:id" [id :as {user-data :identity}]
       (if (= id "1")
        (ring-response/response nil)
         (-> (ring-response/response nil)
             (ring-response/status 404))))
  (GET "/test/:id" [id :as {user-data :identity}]
       (ring-response/response {:username (:username user-data) :user-get-id id}))
  (POST "/test/:id" [id :<< as-int :as {user-data :identity} :as {body :body}]
       (ring-response/response {:username (:username user-data) :user-post-id id :post body}))
  (PUT "/test/:id" [id :as {user-data :identity} :as {body :body}]
       (ring-response/response {:username (:username user-data) :user-put-id id :put body}))
  (DELETE "/test/:id" [id :as {user-data :identity}]
       (ring-response/response {:username (:username user-data) :user-delete-id id}))
  (GET "/test" [] (ring-response/response {:response "user-test"}))
  (route/not-found {:body {:error "Use2r API not found"}}))

(defroutes api-routes
  (GET "/test" [] (ring-response/response {:response "open-test"}))
  (POST "/login" request (comrade-app/login request authenticate))
  (GET "/logout" [] comrade-app/logout)
  (context "/admin" [] admin-api-routes)
  (context "/user" [] user-api-routes)
  (route/not-found {:body {:error "Open API not found"}}))

;; site routes
(defroutes admin-site-routes
  (GET "/" [] "Admin site root")
  (GET "/test/:id" [id :as {user-data :identity}]
       (ring-response/response (str "Admin site " (:username user-data) " id " id)))
  (route/not-found "Admin site not found"))

(defroutes user-site-routes
  (GET "/" [] "User site root")
  (GET "/test/:id" [id :as {user-data :identity}]
       (ring-response/response (str "User site " (:username user-data) " id " id)))
  (route/not-found "User site not found"))

(defroutes site-routes
  (GET "/" [] "Open site root")
  (GET "/test/:id" [id :<< as-int :as {user-data :identity}]
       (ring-response/response (str "Open site " (get user-data :username "[none]") " id " id)))
  (context "/admin" request admin-site-routes)
  (context "/user" request user-site-routes)
  (route/not-found "Open site not found"))

(def restrictions {:admin-api #"^/api/admin($|/.*)"
                   :user-api #"^/api/user($|/.*)"
                   :admin-site #"^/admin($|/.*)"
                   :user-site #"^/user($|/.*)"})

(def session {:session-key "KEEP-KEY-SECRET!"
              :cookie-name "comrade-session"
              :max-age (* 60 60 24 30)})

(def apps
  (list
    {:app (comrade-app/define-app
            :api-routes api-routes
            :site-routes site-routes
            :api-access-denied-fn api-access-denied
            :site-access-denied-fn site-access-denied
            :restrictions restrictions
            :session session)
     :errors {:admin-api-access-denied "Admin API access denied"
              :user-api-access-denied  "User API access denied"
              :admin-site-access-denied "Admin site access denied"
              :user-site-access-denied "User site access denied"}}
    {:app (comrade-app/define-app
            :api-routes api-routes
            :site-routes site-routes
            :restrictions restrictions
            :session session)
     :errors {:admin-api-access-denied "Access denied"
              :user-api-access-denied  "Access denied"
              :admin-site-access-denied "Access denied"
              :user-site-access-denied "Access denied"}}))

(defmacro defreq [method uri cookie response-status response-body & post-data]
  `(concat
     [~(:line (meta &form)) ~method ~uri ~cookie {:status ~response-status :body ~response-body}]
     [~@post-data]))

(defn- get-requests [user-admin-cookie admin-cookie user-cookie
                     {:keys [admin-api-access-denied user-api-access-denied
                             admin-site-access-denied user-site-access-denied]}]
  [(defreq :get "/" user-admin-cookie 200 "Open site root")
   (defreq :get "/" admin-cookie      200 "Open site root")
   (defreq :get "/" user-cookie       200  "Open site root")
   (defreq :get "/" nil               200  "Open site root")
   (defreq :get "/test/1" user-admin-cookie 200  "Open site user-admin-a id 1")
   (defreq :get "/test/1" admin-cookie      200  "Open site admin-b id 1")
   (defreq :get "/test/2" user-cookie       200  "Open site user-c id 2")
   (defreq :get "/test/3" nil               200  "Open site [none] id 3")
   (defreq :get "/not-here" user-admin-cookie 404  "Open site not found")
   (defreq :get "/not-here" admin-cookie      404  "Open site not found")
   (defreq :get "/not-here" user-cookie       404  "Open site not found")
   (defreq :get "/not-here" nil               404  "Open site not found")
   (defreq :get "/admin" user-admin-cookie 200  "Admin site root")
   (defreq :get "/admin" admin-cookie      200  "Admin site root")
   (defreq :get "/admin" user-cookie       403  admin-site-access-denied)
   (defreq :get "/admin" nil               401  admin-site-access-denied)
   (defreq :get "/admin/test/1" user-admin-cookie 200 "Admin site user-admin-a id 1")
   (defreq :get "/admin/test/1" admin-cookie      200 "Admin site admin-b id 1")
   (defreq :get "/admin/test/2" user-cookie       403 admin-site-access-denied)
   (defreq :get "/admin/test/3" nil               401 admin-site-access-denied)
   (defreq :get "/admin/not-here" user-admin-cookie 404 "Admin site not found")
   (defreq :get "/admin/not-here" admin-cookie      404 "Admin site not found")
   (defreq :get "/admin/not-here" user-cookie       403 admin-site-access-denied)
   (defreq :get "/admin/not-here" nil               401 admin-site-access-denied)
   (defreq :get "/user" user-admin-cookie 200 "User site root")
   (defreq :get "/user" admin-cookie      403 user-site-access-denied)
   (defreq :get "/user" user-cookie       200 "User site root")
   (defreq :get "/user" nil               401 user-site-access-denied)
   (defreq :get "/user/test/1" user-admin-cookie 200 "User site user-admin-a id 1")
   (defreq :get "/user/test/1" admin-cookie      403 user-site-access-denied)
   (defreq :get "/user/test/2" user-cookie       200 "User site user-c id 2")
   (defreq :get "/user/test/3" nil               401 user-site-access-denied)
   (defreq :get "/user/not-here" user-admin-cookie 404 "User site not found")
   (defreq :get "/user/not-here" admin-cookie      403 user-site-access-denied)
   (defreq :get "/user/not-here" user-cookie       404 "User site not found")
   (defreq :get "/user/not-here" nil               401 user-site-access-denied)

   (defreq :get "/api/admin/" user-admin-cookie 200  {:username "user-admin-a"})
   (defreq :get "/api/admin/test/12" user-admin-cookie 200 {:username "user-admin-a" :admin-get-id "12"})
   (defreq :post "/api/admin/test/34" user-admin-cookie 200 {:username "user-admin-a" :admin-post-id 34 :post {:foo "bar"}} {:foo "bar"})
   (defreq :put "/api/admin/test/56" user-admin-cookie 200 {:username "user-admin-a" :admin-put-id "56" :put {:foo "bar"}} {:foo "bar"})
   (defreq :delete "/api/admin/test/78" user-admin-cookie 200 {:username "user-admin-a" :admin-delete-id "78"})
   (defreq :head "/api/admin/test/1" user-admin-cookie 200 nil)
   (defreq :head "/api/admin/test/2" user-admin-cookie 404 nil)

   (defreq :get "/api/admin/" admin-cookie 200 {:username "admin-b"})
   (defreq :get "/api/admin/test/12" admin-cookie 200 {:username "admin-b" :admin-get-id "12"})
   (defreq :post "/api/admin/test/34" admin-cookie 200 {:username "admin-b" :admin-post-id 34 :post {:foo "bar"}} {:foo "bar"})
   (defreq :put "/api/admin/test/56" admin-cookie 200 {:username "admin-b" :admin-put-id "56" :put {:foo "bar"}} {:foo "bar"})
   (defreq :delete "/api/admin/test/78" admin-cookie 200 {:username "admin-b" :admin-delete-id "78"})
   (defreq :head "/api/admin/test/1" admin-cookie 200  nil)
   (defreq :head "/api/admin/test/2" admin-cookie 404  nil)

   (defreq :get "/api/admin/" user-cookie 403 {:error admin-api-access-denied})
   (defreq :get "/api/admin/test/12" user-cookie 403 {:error admin-api-access-denied})
   (defreq :post "/api/admin/test/34" user-cookie 403 {:error admin-api-access-denied} {:foo "bar"})
   (defreq :put "/api/admin/test/56" user-cookie 403 {:error admin-api-access-denied} {:foo "bar"})
   (defreq :delete "/api/admin/test/78" user-cookie 403 {:error admin-api-access-denied})
   (defreq :head "/api/admin/test/1" user-cookie 403 nil)
   (defreq :head "/api/admin/test/2" user-cookie 403 nil)

   (defreq :get "/api/admin" nil 401 {:error admin-api-access-denied})
   (defreq :get "/api/admin/test/12" nil 401 {:error admin-api-access-denied})
   (defreq :post "/api/admin/test/34" nil 401 {:error admin-api-access-denied} {:foo "bar"})
   (defreq :put "/api/admin/test/56" nil 401 {:error admin-api-access-denied} {:foo "bar"})
   (defreq :delete "/api/admin/test/78" nil 401 {:error admin-api-access-denied})
   (defreq :head "/api/admin/test/1" nil 401 nil)
   (defreq :head "/api/admin/test/2" nil 401 nil)

   (defreq :get "/api/user" user-admin-cookie 200 {:username "user-admin-a"})
   (defreq :get "/api/user/test/12" user-admin-cookie 200 {:username "user-admin-a" :user-get-id "12"})
   (defreq :post "/api/user/test/34" user-admin-cookie 200 {:username "user-admin-a" :user-post-id 34 :post {:foo "bar"}} {:foo "bar"})
   (defreq :put "/api/user/test/56" user-admin-cookie 200 {:username "user-admin-a" :user-put-id "56" :put {:foo "bar"}} {:foo "bar"})
   (defreq :delete "/api/user/test/78" user-admin-cookie 200 {:username "user-admin-a" :user-delete-id "78"})
   (defreq :head "/api/user/test/1" user-admin-cookie 200 nil)
   (defreq :head "/api/user/test/2" user-admin-cookie 404 nil)

   (defreq :get "/api/user" admin-cookie 403 {:error user-api-access-denied})
   (defreq :get "/api/user/test/12" admin-cookie 403 {:error user-api-access-denied})
   (defreq :post "/api/user/test/34" admin-cookie 403 {:error user-api-access-denied})
   (defreq :put "/api/user/test/56" admin-cookie 403 {:error user-api-access-denied})
   (defreq :delete "/api/user/test/78" admin-cookie 403 {:error user-api-access-denied})
   (defreq :head "/api/user/test/1" admin-cookie 403 nil)
   (defreq :head "/api/user/test/2" admin-cookie 403 nil)

   (defreq :get "/api/user" user-cookie 200 {:username "user-c"})
   (defreq :get "/api/user/test/12" user-cookie 200 {:username "user-c" :user-get-id "12"})
   (defreq :post "/api/user/test/34" user-cookie 200 {:username "user-c" :user-post-id 34 :post {:foo "bar"}} {:foo "bar"})
   (defreq :put "/api/user/test/56" user-cookie 200 {:username "user-c" :user-put-id "56" :put {:foo "bar"}} {:foo "bar"})
   (defreq :delete "/api/user/test/78" user-cookie 200 {:username "user-c" :user-delete-id "78"})
   (defreq :head "/api/user/test/1" user-cookie 200 nil)
   (defreq :head "/api/user/test/2" user-cookie 404 nil)

   (defreq :get "/api/user" nil 401 {:error user-api-access-denied})
   (defreq :get "/api/user/test/12" nil 401 {:error user-api-access-denied})
   (defreq :post "/api/user/test/34" nil 401 {:error user-api-access-denied} {:foo "bar"})
   (defreq :put "/api/user/test/56" nil 401 {:error user-api-access-denied} {:foo "bar"})
   (defreq :delete "/api/user/test/78" nil 401 {:error user-api-access-denied})
   (defreq :head "/api/user/test/1" nil 401 nil)
   (defreq :head "/api/user/test/2" nil 401 nil)])

(defn post [app url data]
  (-> (mock/request :post url)
      (mock/body (json/generate-string data))
      (mock/content-type "application/json")
      (app)))

(defn parse [response]
  {:status (:status response)
   :body (if
           (s/starts-with? (get-in response [:headers "Content-Type"] "") "application/json")
           (json/parse-string (:body response) true)
           (:body response))})

(deftest test-app
  (testing "invalid login"
    (let
      [failure {:status 403, :body {:login "failed"}}
       app (:app (first apps))]
      (is (= (parse (post app "/api/login" {:username "user-c" :password ""})) failure))
      (is (= (parse (post app "/api/login" {:username "user-c" :password "pass-a"})) failure))
      (is (= (parse (post app "/api/login" {:username "" :password "pass-a"})) failure))
      (is (= (parse (post app "/api/login" {:username "" :password ""})) failure))))

  (testing "login for user with no rights"
    (let
      [failure {:status 403, :body {:login "failed"}}
       app (:app (first apps))]
      (is (= (parse (post app "/api/login" {:username "no-rights-d" :password "pass-d"})) failure))))

  (testing "login and logout"
    (let
      [app (:app (first apps))
       first-response (app (mock/request :get "/user"))
       login-response (post app "/api/login" {:username "user-c" :password "pass-c"})
       login-cookie (first (get-in login-response [:headers "Set-Cookie"]))
       second-response (app (mock/header (mock/request :get "/user") "cookie" login-cookie))

       logout-response (app (mock/request :get "/logout"))
       logout-cookie (first (get-in logout-response [:headers "Set-Cookie"]))
       third-response (app (mock/header (mock/request :get "/user") "cookie" logout-cookie))]

      (is (= (parse first-response)  {:status 401, :body "User site access denied"}))
      (is (= (parse second-response) {:status 200, :body "User site root"}))
      (is (= (parse third-response)  {:status 401, :body "User site access denied"}))))

  (testing "extra authorization"
    (let
      [app (comrade-app/define-app
             :api-routes api-routes
             :site-routes site-routes
             :restrictions restrictions
             :session session
             ;; Only allow in authorized requests with uri's ending in 1.
             :allow-access-fn? (fn [request] (= (last (:uri request)) \1)))
       login-response (post app "/api/login" {:username "user-c" :password "pass-c"})
       cookie (first (get-in login-response [:headers "Set-Cookie"]))

       response (app (mock/header (mock/request :get "/user/test/1") "cookie" cookie))
       denied (app (mock/header (mock/request :get "/user/test/2") "cookie" cookie))]

      (is (= (parse response) {:status 200, :body "User site user-c id 1"}))
      (is (= (parse denied)   {:status 403, :body "Access denied"}))))

  (testing "login and route access"
    (doall (for [{app :app errors :errors} apps]
    (let
      [user-admin-response (post app "/api/login" {:username "user-admin-a" :password "pass-a"})
       admin-response (post app "/api/login" {:username "admin-b" :password "pass-b"})
       user-response (post app "/api/login" {:username "user-c" :password "pass-c"})
       user-admin-cookie (first (get-in user-admin-response [:headers "Set-Cookie"]))
       admin-cookie (first (get-in admin-response [:headers "Set-Cookie"]))
       user-cookie (first (get-in user-response [:headers "Set-Cookie"]))]

      (is (= (parse user-admin-response) {:status 200, :body {:login "ok" :admin true :user true}}))
      (is (s/starts-with? admin-cookie "comrade-session="))
      (is (= (parse admin-response) {:status 200, :body {:login "ok" :admin true}}))
      (is (s/starts-with? admin-cookie "comrade-session="))
      (is (= (parse user-response) {:status 200, :body {:login "ok" :user true}}))
      (is (s/starts-with? user-cookie "comrade-session="))

      (doall
        (for [[line-number method uri cookie expected & data]
              (get-requests user-admin-cookie admin-cookie user-cookie errors)]
          (let
            [string-data (if-not (nil? data) (json/generate-string (first data)))
             response (-> (mock/request method uri)
                          (as-> req (if-not (nil? cookie) (mock/header req "cookie" cookie) req))
                          (as-> req (if-not (nil? data) (mock/body req string-data) req))
                          (as-> req (if-not (nil? data) (mock/content-type req "application/json") req))
                          (app)
                          (parse))]
            (is (= response expected) (str "Failed request defined on line " line-number))))))))))
