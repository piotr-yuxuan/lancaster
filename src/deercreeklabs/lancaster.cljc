(ns deercreeklabs.lancaster
  (:require
   [clojure.string :as str]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.lancaster.impl :as impl]
   [deercreeklabs.lancaster.schemas :as schemas]
   [deercreeklabs.lancaster.utils :as u]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   #?(:cljs [goog.math :as gm])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:cljs
     (:require-macros deercreeklabs.lancaster)))

(declare make-name*)

#?(:cljs (def Long gm/Long))

(def int-schema (schemas/make-primitive-schema :int))
(def null-schema (schemas/make-primitive-schema :null))
(def boolean-schema (schemas/make-primitive-schema :boolean))
(def long-schema (schemas/make-primitive-schema :long))
(def float-schema (schemas/make-primitive-schema :float))
(def double-schema (schemas/make-primitive-schema :double))
(def bytes-schema (schemas/make-primitive-schema :bytes))
(def string-schema (schemas/make-primitive-schema :string))

(s/defn make-record-schema :- (s/protocol u/ILancasterSchema)
  [name-kw :- s/Keyword
   fields :- [schemas/RecordFieldDef]]
  (schemas/make-schema :record name-kw fields))

(s/defn make-enum-schema :- (s/protocol u/ILancasterSchema)
  [name-kw :- s/Keyword
   symbols :- [s/Keyword]]
  (schemas/make-schema :enum name-kw symbols))

(s/defn make-fixed-schema :- (s/protocol u/ILancasterSchema)
  [name-kw :- s/Keyword
   size :- s/Int]
  (schemas/make-schema :fixed name-kw size))

(s/defn make-array-schema :- (s/protocol u/ILancasterSchema)
  [items-schema :- (s/protocol u/ILancasterSchema)]
  (schemas/make-schema :array nil items-schema))

(s/defn make-map-schema :- (s/protocol u/ILancasterSchema)
  [values-schema :- (s/protocol u/ILancasterSchema)]
  (schemas/make-schema :map nil values-schema))

(s/defn make-union-schema :- (s/protocol u/ILancasterSchema)
  [members :- [(s/protocol u/ILancasterSchema)]]
  (schemas/make-schema :union nil members))

(s/defn merge-record-schemas :- (s/protocol u/ILancasterSchema)
  [name-kw :- s/Keyword
   schemas :- [(s/protocol u/ILancasterSchema)]]
  (schemas/merge-record-schemas name-kw schemas))

(s/defn maybe :- (s/protocol u/ILancasterSchema)
  [schema :- (s/protocol u/ILancasterSchema)]
  (make-union-schema [null-schema schema]))

(s/defn serialize :- ba/ByteArray
  [schema-obj :- (s/protocol u/ILancasterSchema)
   data :- s/Any]
  (when-not (satisfies? u/ILancasterSchema schema-obj)
    (throw
     (ex-info (str "First argument to serialize must be a schema "
                   "object. The object must satisfy the ILancasterSchema "
                   "protocol.")
              {:schema-obj schema-obj
               :schema-obj-type (#?(:clj class :cljs type) schema-obj)})))
  ;; TODO: Figure out how to set initial size better
  (let [os (impl/make-output-stream 100)]
    (u/serialize schema-obj os data)
    (u/to-byte-array os)))

(s/defn deserialize :- s/Any
  [reader-schema-obj :- (s/protocol u/ILancasterSchema)
   writer-pcf :- s/Str
   ba :- ba/ByteArray]
  (when-not (satisfies? u/ILancasterSchema reader-schema-obj)
    (throw
     (ex-info (str "First argument to deserialize must be a schema "
                   "object representing the reader's schema. The object "
                   "must satisfy the ILancasterSchema protocol.")
              {:reader-schema-obj reader-schema-obj
               :reader-schema-obj-type
               (#?(:clj class :cljs type) reader-schema-obj)})))
  (when-not (string? writer-pcf)
    (throw
     (ex-info (str "Second argument to deserialize must be a string "
                   "representing the parsing canonical form of the "
                   "writer's schema.")
              {:writer-pcf writer-pcf
               :writer-pcf-type (#?(:clj class :cljs type) writer-pcf)})))
  (when-not (instance? ba/ByteArray ba)
    (throw (ex-info "Third argument to deserialize must be a byte array."
                    {:ba ba
                     :ba-type (#?(:clj class :cljs type) ba)})))
  (let [is (impl/make-input-stream ba)]
    (u/deserialize reader-schema-obj writer-pcf is)))

(s/defn wrap :- schemas/WrappedData
  [schema :- (s/protocol u/ILancasterSchema)
   data :- s/Any]
  (u/wrap schema data))

(s/defn get-edn-schema :- s/Any
  [schema :- (s/protocol u/ILancasterSchema)]
  (u/get-edn-schema schema))

(s/defn get-json-schema :- s/Str
  [schema :- (s/protocol u/ILancasterSchema)]
  (u/get-json-schema schema))

(s/defn get-plumatic-schema :- s/Any
  [schema :- (s/protocol u/ILancasterSchema)]
  (u/get-plumatic-schema schema))

(s/defn get-parsing-canonical-form :- s/Str
  [schema :- (s/protocol u/ILancasterSchema)]
  (u/get-parsing-canonical-form schema))

(s/defn get-fingerprint64 :- Long
  [schema :- (s/protocol u/ILancasterSchema)]
  (u/get-fingerprint64 schema))

(s/defn schema? :- s/Bool
  [arg :- s/Any]
  (satisfies? u/ILancasterSchema arg))

;;;;;;;;;; Named Schema Helper Macros ;;;;;;;;;;;;;;;;

(defmacro def-record-schema
  [clj-name & fields]
  (let [ns-name (str (or
                      (:name (:ns &env)) ;; cljs
                      *ns*))             ;; clj
        schema-name (u/make-schema-name clj-name)]
    `(def ~clj-name
       (schemas/make-schema :record ~ns-name ~schema-name (vector ~@fields)))))

(defmacro def-enum-schema
  [clj-name & symbols]
  (let [ns-name (str (or
                      (:name (:ns &env)) ;; cljs
                      *ns*))             ;; clj
        schema-name (u/make-schema-name clj-name)]
    `(def ~clj-name
       (schemas/make-schema :enum ~ns-name ~schema-name (vector ~@symbols)))))

(defmacro def-fixed-schema
  [clj-name size]
  (let [ns-name (str (or
                      (:name (:ns &env)) ;; cljs
                      *ns*))             ;; clj
        schema-name (u/make-schema-name clj-name)]
    `(def ~clj-name
       (schemas/make-schema :fixed ~ns-name ~schema-name ~size))))
