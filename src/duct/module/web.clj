(ns duct.module.web
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [duct.core :as core]
            [duct.core.env :as env]
            [duct.core.merge :as merge]
            [integrant.core :as ig]
            [ring.middleware.defaults :as defaults]))

(def ^:private server-port
  (env/env '["PORT" Int :or 3000]))

(defn- get-environment [config options]
  (:environment options (:duct.core/environment config :production)))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- name-to-path [sym]
  (-> sym name (str/replace "-" "_") (str/replace "." "/")))

(defn- derived-key [m k default]
  (if-let [kv (ig/find-derived-1 m k)] (key kv) default))

(defn- http-server-key [config]
  (derived-key config :duct.server/http :duct.server.http/jetty))

(defn- server-config [config]
  {(http-server-key config) {:port (merge/displace server-port)}})

(defn- router-config [config]
  (if-not (ig/find-derived-1 config :duct/router)
    {:duct.router/cascading []}
    {}))

(def ^:private logging-config
  {:duct.middleware.web/log-requests {:logger (ig/ref :duct/logger)}
   :duct.middleware.web/log-errors   {:logger (ig/ref :duct/logger)}
   :duct.core/handler
   {:middleware ^:distinct [(ig/ref :duct.middleware.web/log-requests)
                            (ig/ref :duct.middleware.web/log-errors)]}})

(def ^:private error-configs
  {:production
   {:duct.core/handler {:middleware ^:distinct [(ig/ref :duct.middleware.web/hide-errors)]}}
   :development
   {:duct.core/handler {:middleware ^:distinct [(ig/ref :duct.middleware.web/stacktrace)]}}})

(def ^:private common-config
  {:duct.middleware.web/not-found
   {:error-handler (merge/displace (ig/ref :duct.handler.static/not-found))}
   :duct.middleware.web/hide-errors
   {:error-handler (merge/displace (ig/ref :duct.handler.static/internal-server-error))}
   :duct.middleware.web/stacktrace {}
   :duct.core/handler {:router  (merge/displace (ig/ref :duct/router))}
   :duct.server/http  {:handler (merge/displace (ig/ref :duct.core/handler))
                       :logger  (merge/displace (ig/ref :duct/logger))}})

(defn- plaintext-response [text]
  ^:demote {:headers {"Content-Type" "text/plain; charset=UTF-8"}, :body text})

(defn- html-response [html]
  ^:demote {:headers {"Content-Type" "text/html; charset=UTF-8"}, :body html})

(def ^:private base-config
  {:duct.handler.static/bad-request           (plaintext-response "Bad Request")
   :duct.handler.static/not-found             (plaintext-response "Not Found")
   :duct.handler.static/method-not-allowed    (plaintext-response "Method Not Allowed")
   :duct.handler.static/internal-server-error (plaintext-response "Internal Server Error")
   :duct.middleware.web/defaults              (with-meta defaults/api-defaults {:demote true})
   :duct.core/handler
   {:middleware ^:distinct [(ig/ref :duct.middleware.web/not-found)
                            (ig/ref :duct.middleware.web/defaults)]}})

(def ^:private api-config
  {:duct.handler.static/bad-request           {:body ^:displace {:error :bad-request}}
   :duct.handler.static/not-found             {:body ^:displace {:error :not-found}}
   :duct.handler.static/method-not-allowed    {:body ^:displace {:error :method-not-allowed}}
   :duct.handler.static/internal-server-error
   {:body ^:displace {:error :internal-server-error}}
   :duct.middleware.web/format   {}
   :duct.middleware.web/defaults (with-meta defaults/api-defaults {:demote true})
   :duct.core/handler
   {:middleware ^:distinct [(ig/ref :duct.middleware.web/not-found)
                            (ig/ref :duct.middleware.web/format)
                            (ig/ref :duct.middleware.web/defaults)]}})

(def ^:private error-400 (io/resource "duct/module/web/errors/400.html"))
(def ^:private error-404 (io/resource "duct/module/web/errors/404.html"))
(def ^:private error-405 (io/resource "duct/module/web/errors/405.html"))
(def ^:private error-500 (io/resource "duct/module/web/errors/500.html"))

(defn- site-resource-paths [project-ns]
  ["duct/module/web/public" (str (name-to-path project-ns) "/public")])

(defn- site-defaults [project-ns]
  (assoc-in defaults/site-defaults [:static :resources] (site-resource-paths project-ns)))

(defn- site-config [project-ns]
  {:duct.handler.static/bad-request           (html-response error-400)
   :duct.handler.static/not-found             (html-response error-404)
   :duct.handler.static/method-not-allowed    (html-response error-405)
   :duct.handler.static/internal-server-error (html-response error-500)
   :duct.middleware.web/webjars  {}
   :duct.middleware.web/defaults (with-meta (site-defaults project-ns) {:demote true})
   :duct.core/handler
   {:middleware ^:distinct [(ig/ref :duct.middleware.web/not-found)
                            (ig/ref :duct.middleware.web/webjars)
                            (ig/ref :duct.middleware.web/defaults)]}})

(defn- apply-web-module [config options module-config]
  (core/merge-configs config
                      (server-config config)
                      (router-config config)
                      common-config
                      module-config
                      logging-config
                      (error-configs (get-environment config options))))

(defmethod ig/init-key :duct.module/web [_ options]
  {:req #{:duct/logger}
   :fn  #(apply-web-module % options base-config)})

(defmethod ig/init-key ::api [_ options]
  {:req #{:duct/logger}
   :fn  #(apply-web-module % options api-config)})

(defmethod ig/init-key ::site [_ options]
  {:req #{:duct/logger}
   :fn  #(apply-web-module % options (site-config (get-project-ns % options)))})
