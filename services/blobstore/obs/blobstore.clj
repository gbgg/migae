(ns appengine-magic.service.user
  (:require [appengine-magic.kernel :as core]
            [appengine-magic.service.datastore :as ds]
            [appengine-magic.service.url-fetch :as url])
  (:import [com.google.appengine.api.blobstore ByteRange BlobKey
            BlobstoreService BlobstoreServiceFactory]
           [javax.servlet.http HttpServletRequest HttpServletResponse]
           org.apache.commons.io.IOUtils))


(defonce ^{:dynamic true} *blobstore-service* (atom nil))

;;gar from local
(defn appengine-base-url [& {:keys [https?] :or {https? false}}]
  ;; NB: The https? argument is intentionally ignored. HTTPS is not supported
  ;; for local environments.
  ;; (str "http://localhost:"
  ;;      (str @appengine-magic.lib.local-env-helpers/*current-server-port*)))
;;gar from core_google
(defn appengine-base-url [& {:keys [https?] :or {https? false}}]
  (when (= :dev-appserver (appengine-environment-type))
    (throw (RuntimeException.
            "appengine-magic.lib.core/appengine-base-url not supported in dev-appserver.sh")))
  (str (if https? "https" "http")
       "://" (appengine-app-id) ".appspot.com"))


(defn get-blobstore-service []
  (when (nil? @*blobstore-service*)
    (reset! *blobstore-service* (BlobstoreServiceFactory/getBlobstoreService)))
  @*blobstore-service*)


(defn upload-url [success-path]
  (.createUploadUrl (get-blobstore-service) success-path))


(defn delete! [& blobs]
  (let [blobs (map ds/as-blob-key blobs)]
    (.delete (get-blobstore-service) (into-array blobs))))


(defn fetch-data [^:BlobKey blob-key, start-index, end-index]
  (.fetchData (get-blobstore-service) blob-key start-index end-index))


(defn byte-range [^:HttpServletRequest request]
  (.getByteRange (get-blobstore-service) request))


(defn- serve-helper
  ([blob-key, ^:HttpServletResponse response]
     (.serve (get-blobstore-service) (ds/as-blob-key blob-key) response))
  ([blob-key, start, end, ^:HttpServletResponse response]
     (.serve (get-blobstore-service) (ds/as-blob-key blob-key) (ByteRange. start end) response)))


(defn serve [request blob-key]
  (serve-helper blob-key (:response request))
  ;; This returns a special Ring response map. The serve-helper primes the HTTP
  ;; response object, but this response must not be committed by the running servlet.
  {:commit? false})


(defn callback-complete [request destination]
  (.sendRedirect (:response request) destination)
  {:commit? false})


(defn upload-hack [contents success-path & {:keys [headers] :or {headers {}}}]
  "This allows uploading arbitrary data to the Blobstore. This is a temporary workaround,
   meant to last only until the App Engine SDK provides a cleaner
   implementation."
  (let [contents (if (sequential? contents) contents [contents])
        boundary (str (java.util.UUID/randomUUID))
        payload-content-type (str "multipart/form-data; boundary=" boundary)
        payload (with-open [output-stream (java.io.ByteArrayOutputStream.)]
                  (doseq [{:keys [field filename content-type bytes]} contents]
                    (IOUtils/write (format "--%s\r\n" boundary) output-stream)
                    (IOUtils/write
                     (format "Content-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                             field filename)
                     output-stream)
                    (IOUtils/write
                     (format "Content-Type: %s\r\n\r\n" content-type)
                     output-stream)
                    (IOUtils/write bytes output-stream)
                    (IOUtils/write "\r\n" output-stream))
                  (IOUtils/write (format "--%s--\r\n" boundary) output-stream)
                  (.toByteArray output-stream))
        upload-target (upload-url success-path)]
    (url/fetch (condp = (core/appengine-environment-type)
                   :production upload-target
                   :interactive (str (core/appengine-base-url) upload-target)
                   :dev-appserver (throw (RuntimeException.
                                          "upload-hack not supported in dev_appserver.sh")))
               :method :post
               :follow-redirects false
               :headers (merge headers {"Content-Type" payload-content-type})
               :payload payload)))


(if (= :interactive (core/appengine-environment-type))
    (load "blobstore_local")
    (load "blobstore_google"))
