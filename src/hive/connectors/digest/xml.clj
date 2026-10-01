(ns hive.connectors.digest.xml
  "Pure. A hardened DOM parser and the few element helpers the digest reads
   feeds and Maven metadata with. DOCTYPEs are refused, so a hostile
   document cannot expand entities or read local files."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (javax.xml.parsers DocumentBuilder DocumentBuilderFactory)
           (org.w3c.dom Element Node NodeList)
           (org.xml.sax ErrorHandler)))

;; SPDX-License-Identifier: MIT

(defn- builder ^DocumentBuilder []
  (let [f (DocumentBuilderFactory/newInstance)]
    (.setNamespaceAware f true)
    (.setFeature f "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setXIncludeAware f false)
    (.setExpandEntityReferences f false)
    (doto (.newDocumentBuilder f)
      ;; The default handler prints to stderr before throwing; the throw alone
      ;; carries the error to the caller, which reports it per source.
      (.setErrorHandler (reify ErrorHandler
                          (warning [_ _])
                          (error [_ e] (throw e))
                          (fatalError [_ e] (throw e)))))))

(defn root
  "XML string -> its document element. Throws on malformed input or a DOCTYPE."
  ^Element [^String s]
  (.getDocumentElement (.parse (builder) (ByteArrayInputStream. (.getBytes s StandardCharsets/UTF_8)))))

(defn local-name [^Node n]
  (or (.getLocalName n) (.getNodeName n)))

(defn children [^Node n]
  (let [^NodeList nl (.getChildNodes n)]
    (for [i (range (.getLength nl))
          :let [c (.item nl i)]
          :when (= Node/ELEMENT_NODE (.getNodeType c))]
      c)))

(defn kids [n nm] (filter #(= nm (local-name %)) (children n)))

(defn child [n nm] (first (kids n nm)))

(defn content [n] (some-> ^Node n .getTextContent str/trim not-empty))
