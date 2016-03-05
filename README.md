# Comrade

Comrade is a pretty opinionated wrapper around [Ring](https://github.com/ring-clojure),
[Compojure](https://github.com/weavejester/compojure), and [Buddy](https://github.com/funcool/buddy-auth)
to build web applications that contain both a site and a RESTful API.

The goal of comrade is to hide away some of the complexities with implementing an
authorized and authenticated site, while at the same time serving up a RESTful API
through the same app.

## Installation

~~To include comrade into your app, add the following to your project `:dependencies`:~~

```clojure
[comrade/app "0.1.0"]
```

__Note that the library has not yet been pushed to clojars. Please build
a local copy with `lein install`.__

## Getting started

The following example defines a simple web app, with an authentication function,
and a few site and admin routes. The route restrictions are defined at the bottom,
using the standard Buddy access [rules](https://funcool.github.io/buddy-auth/latest/#access-rules).

A fuller example, showing the customizations available to comrade, combined
with service static content is available at
[comrade-examples](https://github.com/gandalfhz/comrade-examples).

```clojure
(ns comrade-examples.simple-handler
  (:require [compojure.core :refer [defroutes routes context GET POST]]
            [compojure.route :as route]
            [ring.util.response :as ring-response]
            [comrade.app :as comrade-app]))

;; Define your authentication function. A non-nil response indicates the login was successful.
(defn authenticate [username password]
  (cond
    (and (= username "admin") (= password "admin-password")) {:username username :admin true}
    (and (= username "user") (= password "user-password")) {:username username :user true}))

;; REST routes
(defroutes admin-api-routes (GET "/ping" [] (ring-response/response {:ping "admin"})))
(defroutes user-api-routes (GET "/ping" [] (ring-response/response {:ping "user"})))

(defroutes api-routes
  (POST "/login" request (comrade-app/login request authenticate))
  (GET "/logout" [] comrade-app/logout)
  (context "/admin" [] admin-api-routes)
  (context "/user" [] user-api-routes)
  (route/not-found {:body {:error "Not found"}}))

;; site routes
(defroutes admin-site-routes (GET "/" [] "Admin site root"))
(defroutes user-site-routes (GET "/" [] "User site root"))

(defroutes site-routes
  (GET "/" [] "Site root")
  (context "/admin" request admin-site-routes)
  (context "/user" request user-site-routes)
  (route/not-found "Not Found"))

(def app
  (comrade-app/define-app
    :api-routes api-routes
    :site-routes site-routes
    :restrictions {:admin-api #"^/api/admin($|/.*)"
                   :user-api #"^/api/user($|/.*)"
                   :admin-site #"^/admin($|/.*)"
                   :user-site #"^/user($|/.*)"}
    :session {:session-key "KEEP-KEY-SECRET!"
              :cookie-name "comrade-simple-example"
              :max-age (* 60 60 24 30)}))
```

## Application configuration

The example above lists the minimal configuration needed. Below is a list of all
available parameters.

```clojure
  :api-routes ;; compojure REST API routes.
  :site-routes ;; compojure routes.
  :restrictions
    :admin-api ;; regex for REST API paths needing admin access.
    :user-api ;; regex for REST API paths needing user access.
    :admin-site ;; regex for site paths needing admin access.
    :user-site ;; regex for site paths needing user access.
  :session
    :session-key ;; session key used for cookie encryption. Keep this secret.
    :cookie-name ;; name of cookie, can be pretty much anything.
    :max-age ;; cookie expiration in seconds.
  :api-access-denied-fn ;; function to return custom access denied responses (API) (optional).
  :site-access-denied-fn ;; function to return custom access denied responses (site) (optional).
  :allow-access-fn? ;; function to do additional authorization of each restricted call (optional).
    ;; Note that this function will be called on every request for a restricted route,
    ;; even if the request was denied based on the user not being authenticated,
    ;; or having the wrong type (user vs. admin).
  :api-defaults ;; custom settings to pass to ring.middleware.defaults/wrap-defaults.
    ;; Defaults to ring.middleware.defaults/api-defaults (optional).
  :site-defaults ;; custom settings to pass to ring.middleware.defaults/wrap-defaults.
    ;; Defaults to ring.middleware.defaults/site-defaults (optional)."
```

For examples of using all the parameters, look at
[comrade-examples](https://github.com/gandalfhz/comrade-examples),
as well as the [tests](https://github.com/gandalfhz/comrade/blob/master/test/comrade/app_test.clj).

## Maturity

Since this project is new the APIs are subject to slight changes.

## Thanks

Thanks to the makers of the excellent frameworks [Ring](https://github.com/ring-clojure),
[Compojure](https://github.com/weavejester/compojure),
and [Buddy](https://github.com/funcool/buddy-auth).

## License

Copyright © 2016 Gandalf Hernandez

Distributed under the Apache 2.0 license.
