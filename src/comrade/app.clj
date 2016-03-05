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
(ns comrade.app
  (:require [compojure.core :as core]
            [compojure.route :as route]
            [ring.middleware.json :as ring-json]
            [ring.middleware.session :as ring-session]
            [ring.middleware.session.cookie :as ring-cookie]
            [ring.middleware.defaults :as ring-defaults]
            [ring.util.response :as ring-response]
            [buddy.auth :as buddy-auth]
            [buddy.auth.backends.session :as buddy-session]
            [buddy.auth.accessrules :as buddy-accessrules]
            [buddy.auth.middleware :as buddy-middleware]
            [cheshire.core :as json]))

(defn login
  "The authentication function is expected to return a map with a
  key :admin set to non-nil, if the account is an administrator.
  If the account is a user, the key :user should be set to non-nil.
  An account can be both a user an an admin.  If the login
  failed, nil should be returned. A user with no rights, is treated
  as a failure."
  [request authentication-fn]
  (let
    [username (get-in request [:body :username])
     password (get-in request [:body :password])
     user-data (authentication-fn username password)]
    (if
      (and (some? user-data) (or (contains? user-data :admin) (contains? user-data :user)))
      (-> (ring-response/response
           (-> {:login "ok"}
               (as-> d (if (contains? user-data :admin) (assoc d :admin true) d))
               (as-> d (if (contains? user-data :user) (assoc d :user true) d))))
          (assoc :session (assoc (:session request) :identity user-data)))
      (-> (ring-response/response {:login "failed"})
          (ring-response/status 403)))))

(defn logout
  "Abandon the session."
  [request]
  (-> (ring-response/response {:logout "ok"})
      (assoc :session {})))

(defn- is-admin? [request]
  (some? (get-in request [:identity :admin])))

(defn- is-user? [request]
  (some? (get-in request [:identity :user])))

;; If the user is authenticated and is denied we send a 403,
;; otherwise they get a 401, telling them to authenticate.
;; If the request is a head, we send no body.
(defn- deny-api-request [api-access-denied-fn tried-admin]
  (fn [request value]
    (let [suggested-code (if (buddy-auth/authenticated? request) 403 401)]
      (if-not
        (= (:request-method request) :head)
        (-> (api-access-denied-fn request tried-admin suggested-code)
            (as-> response (if (coll? (:body response))
                             (update-in response [:body] json/generate-string)
                             response))
            (as-> response (if-not (contains? (:headers response) "Content-Type")
                             (ring-response/content-type response "application/json; charset=utf-8")
                             response)))
        (-> (ring-response/response nil)
            (ring-response/status suggested-code))))))

(defn- deny-site-request [site-access-denied-fn tried-admin]
  (fn [request value]
    (-> (site-access-denied-fn request tried-admin (if (buddy-auth/authenticated? request) 403 401))
        (as-> response (if-not (contains? (:headers response) "Content-Type")
                         (ring-response/content-type response "text/html; charset=utf-8")
                         response)))))

(defn- get-rules [admin-api user-api admin-site user-site
                  api-access-denied-fn site-access-denied-fn
                  allow-access-fn?]
  [{:pattern admin-api
    :handler {:and [buddy-auth/authenticated? is-admin? allow-access-fn?]}
    :on-error (deny-api-request api-access-denied-fn true)}
   {:pattern user-api
    :handler {:and [buddy-auth/authenticated? is-user? allow-access-fn?]}
    :on-error (deny-api-request api-access-denied-fn false)}
   {:pattern admin-site
    :handler {:and [buddy-auth/authenticated? is-admin? allow-access-fn?]}
    :on-error (deny-site-request site-access-denied-fn true)}
   {:pattern user-site
    :handler {:and [buddy-auth/authenticated? is-user? allow-access-fn?]}
    :on-error (deny-site-request site-access-denied-fn false)}])

(defn- api-access-denied [request tried-admin suggested-code]
  (-> (ring-response/response (json/generate-string {:error "Access denied"}))
      (ring-response/status suggested-code)))

(defn- site-access-denied [request tried-admin suggested-code]
  (-> (ring-response/response "Access denied")
      (ring-response/status suggested-code)))

(defn define-app
  "Define a web app, with the passed in parameters. Parameters are:
    :api-routes - compojure REST API routes.
    :site-routes - compojure routes.
    :restrictions
      :admin-api - regex for REST API paths needing admin access.
      :user-api - regex for REST API paths needing user access.
      :admin-site - regex for site paths needing admin access.
      :user-site - regex for site paths needing user access.
    :session
      :session-key - session key used for cookie encryption. Keep this secret.
      :cookie-name - name of cookie, can be pretty much anything.
      :max-age - cookie expiration in seconds.
    :api-access-denied-fn - function to return custom access denied responses (API) (optional).
    :site-access-denied-fn - function to return custom access denied responses (site) (optional).
    :allow-access-fn? - function to do additional authorization of each restricted call (optional).
      Note that this function will be called on every request for a restricted route,
      even if the request was denied based on the user not being authenticated,
      or having the wrong type (user vs. admin).
    :api-defaults - custom settings to pass to ring.middleware.defaults/wrap-defaults.
      Defaults to ring.middleware.defaults/api-defaults (optional).
    :site-defaults - custom settings to pass to ring.middleware.defaults/wrap-defaults.
      Defaults to ring.middleware.defaults/site-defaults (optional)."
  [& {:keys [api-routes site-routes api-access-denied-fn site-access-denied-fn
             allow-access-fn? api-defaults site-defaults]
      :or {api-access-denied-fn api-access-denied
           site-access-denied-fn site-access-denied
           allow-access-fn? (fn [request] true)
           api-defaults ring-defaults/api-defaults
           site-defaults ring-defaults/site-defaults}
      {:keys [admin-api user-api admin-site user-site]} :restrictions
      {:keys [session-key cookie-name max-age]} :session}]
  (let
    [api (-> (core/context "/api" request api-routes)
             (ring-json/wrap-json-body {:keywords? true})
             (ring-json/wrap-json-response)
             (ring-defaults/wrap-defaults api-defaults))
     site (ring-defaults/wrap-defaults site-routes site-defaults)]
    (-> (core/routes api site)
        (buddy-accessrules/wrap-access-rules
          {:rules (get-rules admin-api user-api admin-site user-site
                             api-access-denied-fn site-access-denied-fn
                             allow-access-fn?)})
        (buddy-middleware/wrap-authentication (buddy-session/session-backend))
        (ring-session/wrap-session
          {:store (ring-cookie/cookie-store {:key session-key})
           :cookie-name cookie-name
           :cookie-attrs {:max-age max-age}}))))
