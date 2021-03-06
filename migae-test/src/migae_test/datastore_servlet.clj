(ns migae-test.datastore-servlet
    (:gen-class :extends javax.servlet.http.HttpServlet)
    (:require [migae-test.datastore-impl :as impl]
            [ring.util.servlet :as ring]))

(defn -service
  [this rqst resp]
    (let [request-map  (ring/build-request-map rqst)
          response-map (impl/datastore-handler request-map)]
    (when response-map
      (ring/update-servlet-response resp response-map))))
