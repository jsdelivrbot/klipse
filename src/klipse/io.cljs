(ns klipse.io
  (:require-macros [gadjett.core :refer [dbg]]
                   [purnam.core :refer [!>]]
                   [cljs.core.async.macros :refer [go go-loop]])
  (:require
    [clojure.walk :as ww]
    [clojure.string :as string :refer [join split lower-case]]
    [cljs-http.client :as http]
    [cljs-http.util :refer [transit-decode]]
    [cljs.core.async :refer [<!]]))

(defn edn [json]
  (-> json
      clj->js
      js/JSON.stringify
      (transit-decode :json nil)))

(def macro-suffixes [".clj" ".cljc"])
(def cljs-suffixes [".cljs" ".cljc"])

(defmulti load-ns
  "
  Each runtime environment provides a different way to load a library.
  Received two arguments - a map and a callback function:
  The map will have the following keys:

    :name   - the name of the library (a symbol)
    :macros - modifier signaling a macros namespace load
    :path   - munged relative library path (a string)

    It is up to the implementor to correctly resolve the corresponding .cljs,
    .cljc, or .js resource (the order must be respected).
    If :macros is true, resolution should only consider .clj or .cljc resources (the order must be respected).
  Upon resolution the callback should be invoked with a map containing the following keys:

    :lang       - the language, :clj or :js
    :source     - the source of the library (a string)
    :file       - optional, the file path, it will be added to AST's :file keyword (but not in :meta)
    :cache      - optional, if a :clj namespace has been precompiled to :js, can give an analysis cache for faster loads.
    :source-map - optional, if a :clj namespace has been precompiled to :js, can give a V3 source map JSON

    If the resource could not be resolved, the callback should be invoked with
    nil."
  (fn [_ {:keys [name macros path]} src-cb]
                    [name macros path]
                  (cond
                    macros :macro
                    (re-matches #"^goog\..*" (str name)) :goog
                    (re-matches #".*\.gist-.*" (str name)) :gist
                    :default :cljs)))

;https://github.com/clojure/clojurescript/blob/master/src/test/self/self_parity/test.cljs#L166
;Indicates namespaces that we either don't need to load,
;shouldn't load, or cannot load (owing to unresolved
;                                          technical issues).

(def skip-ns-macros #{'cljs.core
                      'cljs.pprint
                      'cljs.env.macros
                      'cljs.analyzer.macros
                      'cljs.js
                      'cljs.compiler.macros})

(def the-ns-map '{cljs.spec "https://raw.githubusercontent.com/clojure/clojurescript/r1.9.229/src/main/cljs/"
                  cljs.spec.impl.gen "https://raw.githubusercontent.com/clojure/clojurescript/r1.9.229/src/main/cljs/"})

(def skip-ns-cljs #{'cljs.core
                    'cljs.env
                    'cljs.source-map
                    'cljs.tools.reader.reader-types
                    'cljs.tools.reader})

(defn try-to-load-ns
  ([filenames lang src-key src-cb] (try-to-load-ns filenames lang src-key src-cb identity))
  ([filenames lang src-key src-cb transform-body-fn]
   (go
     (when-not (loop [filenames filenames]
                 (when (seq filenames)
                   (let [filename (first filenames)
                         {:keys [status body]} (<! (http/get filename {:with-credentials? false}))]
                     (if (= 200 status)
                       (do (src-cb {:lang lang src-key (transform-body-fn body) :file filename})
                           :success)
                       (do (recur (rest filenames)))))))
       (src-cb nil)))))


(defn external-libs-files
  "returns a list of files provided list of external-libs and suffixes"
  [external-libs suffixes path]
  (for [lib external-libs
        suffix suffixes]
    (str lib "/" path suffix)))

(defmethod load-ns :macro [external-libs {:keys [name path]} src-cb]
  (cond
    (skip-ns-macros name) (src-cb {:lang :clj :source ""})
    (find the-ns-map name) (let [prefix (str (get the-ns-map name) "/" path)
                                 filenames (map (partial str prefix) macro-suffixes)]
                             (try-to-load-ns filenames :clj :source src-cb))
    :else (let [filenames (external-libs-files external-libs macro-suffixes path)]
            (try-to-load-ns filenames :clj :source src-cb))))

(def cache-url "https://storage.googleapis.com/app.klipse.tech/fig/js/")
#_(def cache-url "/cache/js/")

(defmethod load-ns :gist [external-libs {:keys [path]} src-cb]
  (let [path (string/replace path #"gist_" "")
        filenames (map #(str "https://gist.githubusercontent.com/" path %) cljs-suffixes)]
    (try-to-load-ns filenames :clj :source src-cb)))

(defn cached-ns
  "Checks whether a namspace is present at run-time"
  [name]
  (!> js/goog.getObjectByName (str (munge name)))); (:require goog breaks the build see http://dev.clojure.org/jira/browse/CLJS-1677

(defmethod load-ns :cljs [external-libs {:keys [name path]} src-cb]
  (cond (skip-ns-cljs name) (src-cb {:lang :js :source ""})
        (cached-ns name) (let [filenames (map #(str cache-url path % ".cache.json") cljs-suffixes)]
                           (try-to-load-ns filenames :js :cache src-cb edn))
        (find the-ns-map name) (let [prefix (str (get the-ns-map name) "/" path)
                                     filenames (map (partial str prefix) cljs-suffixes)]
                                 (try-to-load-ns filenames :clj :source src-cb))
        (seq external-libs) (let [filenames (external-libs-files external-libs cljs-suffixes path)]
                (try-to-load-ns filenames :clj :source src-cb))
        :else (src-cb nil)))

(defn fix-goog-path [path]; https://github.com/oakes/eval-soup/blob/master/src/eval_soup/core.cljs
  ; goog/string -> goog/string/string
  ; goog/string/StringBuffer -> goog/string/stringbuffer
  (let [parts (split path #"/")
        last-part (last parts)
        new-parts (concat
                    (butlast parts)
                    (if (= last-part (lower-case last-part))
                      [last-part last-part]
                      [(lower-case last-part)]))]
    (join "/" new-parts)))

(defn another-goog-path [path]; https://github.com/oakes/eval-soup/blob/master/src/eval_soup/core.cljs
  ; goog/string/format -> goog/string/stringformat
  (let [parts (split path #"/")
        last-part (last parts)
        new-parts (concat
                    (butlast parts)
                    [(join "" (take-last 2 parts))])]
    (join "/" new-parts)))

(defmethod load-ns :goog [_ {:keys [name path]} src-cb]
  (cond
    (!> js/goog.getObjectByName (str name)) (src-cb {:lang :js :source ""}); isProvided and nameToPath don't work with :optimizations :simple or :whitespace
    :else (let [closure-github-path "https://raw.githubusercontent.com/google/closure-library/v20160713/closure/"
                filenames (map #(str closure-github-path % ".js") ((juxt fix-goog-path identity another-goog-path) path))]
            (try-to-load-ns filenames :js :source src-cb))))

