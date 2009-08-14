;;; http/agent.clj: agent-based asynchronous HTTP client

;; by Stuart Sierra, http://stuartsierra.com/
;; June 8, 2009

;; Copyright (c) Stuart Sierra, 2009. All rights reserved.  The use
;; and distribution terms for this software are covered by the Eclipse
;; Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this
;; distribution.  By using this software in any fashion, you are
;; agreeing to be bound by the terms of this license.  You must not
;; remove this notice, or any other, from this software.


(ns #^{:doc "Agent-based asynchronous HTTP client."}
  clojure.contrib.http.agent
  (:require [clojure.contrib.http.connection :as c]
            [clojure.contrib.duck-streams :as duck]))

(defn- setup-http-connection
  [conn options]
  (.setRequestMethod conn (:method options))
  (.setInstanceFollowRedirects conn (:follow-redirects options))
  (doseq [[name value] (:headers options)]
    (.setRequestProperty conn name value)))

(defn- connection-success? [conn]
  ;; Is the response in the 2xx range?
  (= 2 (unchecked-divide (.getResponseCode conn) 100)))

(defn- do-http-agent-request [state options]
  (let [conn (::connection state)]
    (setup-http-connection conn options)
    (c/start-http-connection conn (:body options))
    (let [bytes (if (connection-success? conn)
                  (duck/to-byte-array (.getInputStream conn))
                  (duck/to-byte-array (.getErrorStream conn)))]
      (.disconnect conn)
      (assoc state
        ::response-body-bytes bytes
        ::state ::completed))))

(def *http-request-defaults*
  {:method "GET"
   :headers {}
   :body nil
   :connect-timeout 0
   :read-timeout 0
   :follow-redirects true})

(defn- completed-watch [success-fn failure-fn key http-agnt old-state new-state]
  (when (and (= (::state new-state) ::completed)
             (not= (::state old-state) ::completed))
   (if (connection-success? (::connection new-state))
     (when success-fn (success-fn http-agnt))
     (when failure-fn (failure-fn http-agnt)))))

(defn http-agent
  "Creates (and immediately returns) an Agent representing an HTTP
  request running in a new thread.

  options are key/value pairs:

  :method string

  The HTTP method name.  Default is \"GET\".

  :headers h

  HTTP headers, as a Map or a sequence of pairs like 
  ([key1,value1], [key2,value2])  Default is nil.

  :body b
  
  HTTP request entity body, one of nil, String, byte[], InputStream,
  Reader, or File.  Default is nil.

  :connect-timeout int

  Timeout value, in milliseconds, when opening a connection to the
  URL.  Default is zero, meaning no timeout.

  :read-timeout int

  Timeout value, in milliseconds, when reading data from the
  connection.  Default is zero, meaning no timeout.

  :follow-redirects boolean

  If true, HTTP 3xx redirects will be followed automatically.  Default
  is true.

  :on-success f

  Function to be called when the request succeeds with a 2xx response
  code.  Default is nil, do nothing.  The function will be called with
  the HTTP agent as its argument.  Any exceptions thrown by this
  function will be added to the agent's error queue (see
  agent-errors).

  :on-failure f

  Function to be called when the request fails with a 4xx or 5xx
  response code.  Default is nil, do nothing.  The function will be
  called with the HTTP agent as its argument.  Any exceptions thrown
  by this function will become agent-errors.  Any exceptions thrown by
  this function will be added to the agent's error queue (see
  agent-errors).
  "
  ([url & options]
     (let [opts (merge *http-request-defaults* (apply array-map options))]
       (let [a (agent {::connection (c/http-connection url)
                       ::state ::created
                       ::url url
                       ::options opts})]
         (when (or (:on-success opts) (:on-failure opts))
           (add-watch a ::completed-watch
                      (partial completed-watch
                               (:on-success opts) (:on-failure opts))))
         (send-off a do-http-agent-request opts)))))

(defn response-body-bytes
  "Returns a Java byte array of the content returned by the server."
  [a]
  (when (= (::state @a) ::completed)
    (::response-body-bytes @a)))

(defn response-body-str
  "Returns the HTTP response body as a string, using the given
  encoding.

  If no encoding is given, uses the encoding specified in the server
  headers, or clojure.contrib.duck-streams/*default-encoding* if it is
  not specified."
  ([http-agnt]
     (response-body-str http-agnt
                        (or (.getContentEncoding (::connection @http-agnt))
                            duck/*default-encoding*)))
  ([http-agnt encoding]
     (let [a @http-agnt]
       (when (= (::state a) ::completed)
         (let [conn (::connection a)
               bytes (::response-body-bytes a)]
           (String. bytes encoding))))))

(defn response-status
  "Returns the Integer response status code (e.g. 200, 404) for this request."
  [a]
  (when (= (::state @a) ::completed)
    (.getResponseCode (::connection @a))))

(defn response-message
  "Returns the HTTP response message (e.g. 'Not Found'), for this request."
  [a]
  (when (= (::state @a) ::completed)
    (.getResponseMessage (::connection @a))))

(defn response-headers
  "Returns a String=>String map of HTTP response headers.  Header
  names are converted to all lower-case.  If a header appears more
  than once, only the last value is returned."
  [a]
  (reduce (fn [m [#^String k v]]
            (assoc m (when k (.toLowerCase k)) (last v)))
          {} (.getHeaderFields (::connection @a))))

(defn response-headers-seq
  "Returns the HTTP response headers in order as a sequence of
  [String,String] pairs.  The first 'header' name may be null for the
  HTTP status line."
  [http-agnt]
  (let [conn (::connection @http-agnt)
        f (fn thisfn [i]
            ;; Get value first because first key may be nil.
            (when-let [value (.getHeaderField conn i)]
              (cons [(.getHeaderFieldKey conn i) value]
                    (thisfn (inc i)))))]
    (lazy-seq (f 0))))

(defn- response-in-range? [digit http-agnt]
  (= digit (unchecked-divide (.getResponseCode (::connection @http-agnt))
                             100)))

(defn success?
  "Returns true if the HTTP response code was in the 200-299 range."
  [http-agnt]
  (response-in-range? 2 http-agnt))

(defn redirect?
  "Returns true if the HTTP response code was in the 300-399 range.

  Note: if the :follow-redirects option was true (the default),
  redirects will be followed automatically and a the agent will never
  return a 3xx response code."
  [http-agnt]
  (response-in-range? 3 http-agnt))

(defn client-error?
  "Returns true if the HTTP response code was in the 400-499 range."
  [http-agnt]
  (response-in-range? 4 http-agnt))

(defn server-error?
  "Returns true if the HTTP response code was in the 500-599 range."
  [http-agnt]
  (response-in-range? 5 http-agnt))

(defn error?
  "Returns true if the HTTP response code was in the 400-499 range OR
  the 500-599 range."
  [http-agnt]
  (or (client-error? http-agnt)
      (server-error? http-agnt)))