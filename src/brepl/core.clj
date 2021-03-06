(ns brepl.core
  (:refer-clojure :exclude [munge load-file loaded-libs macroexpand-1])
  (:use cljs.compiler)
  (:require [brepl.server :as server]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn browser-eval1
  [repl-env env form]
  (try
    (let [ast (analyze env form)
          js (emits ast)]
          (server/send-js js))
    (catch Throwable ex
      (.printStackTrace ex)
      (println (str ex)))))

(defn browser-load-stream [repl-env stream]
  (with-open [r (io/reader stream)]
    (let [env {:ns (@namespaces *cljs-ns*) :context :statement :locals {}}
          pbr (clojure.lang.LineNumberingPushbackReader. r)
          eof (Object.)]
      (loop [r (read pbr false eof false)]
        (let [env (assoc env :ns (@namespaces *cljs-ns*))]
          (when-not (identical? eof r)
            (browser-eval1 repl-env env r)
            (recur (read pbr false eof false))))))))

(defn browser-load-file
  [repl-env f]
  (binding [*cljs-ns* 'cljs.user]
    (let [res (if (= \/ (first f)) f (io/resource f))]
      (assert res (str "Can't find " f " in classpath"))
      ;;TODO: send a script tag?
      ;;(.put ^javax.script.ScriptEngine (:jse repl-env)
      ;;      javax.script.ScriptEngine/FILENAME f)
      (browser-load-stream repl-env res))))

(defn browser-require [[goog-ns _ ns-alias]]
  (swap! namespaces assoc-in [*cljs-ns* :requires ns-alias] goog-ns)
  (server/send-js (str "goog.require(\"" (name goog-ns) "\")")))

(defn try-read []
  (try
    (read)
    (catch Throwable ex
      (.printStackTrace ex)
      (println (str ex)))))

(defn execute-repl [repl-env verbose warn-on-undeclared]
  (println "Repl connected. Let's do this.")
  (binding [*cljs-ns* 'cljs.user
            *cljs-verbose* verbose
            *cljs-warn-on-undeclared* warn-on-undeclared]
    (let [env {:context :statement :locals {}}]
      (browser-load-file repl-env "cljs/core.cljs")
      (browser-eval1 repl-env (assoc env :ns (@namespaces *cljs-ns*))
             '(ns cljs.user))
      (loop []
        (print (str "brepl :: " *cljs-ns* " :: "))
        (flush)
        (let [form (try-read)]
          (cond
            (nil? form) (recur)

            (= form :cljs/quit) (do 
                                  (server/stop-repl-server)
                                  :quit)

            (and (seq? form) (= (first form) 'in-ns))
            (do (set! *cljs-ns* (second (second form))) (newline) (recur))

            (and (seq? form) (= (first form) 'list-ns))
            (do (println @namespaces) (newline) (recur))

            (and (seq? form) (= (first form) 'require))
            (let [req (second form)
                  req (if (vector? req)
                        req
                        (second req))]
              (do (browser-require req) (recur)))

            (and (seq? form) ('#{load-file clojure.core/load-file} (first form)))
            (do (load-file repl-env (second form)) (newline) (recur))

            :else
            (let [ret (browser-eval1 repl-env
                                     (assoc env :ns (@namespaces *cljs-ns*))
                                     form)]
              (recur))))))))

(defn browser-repl
  "Note - repl will reload core.cljs every time, even if supplied old repl-env"
  [repl-env & {:keys [verbose warn-on-undeclared]}]
  (prn "Starting repl server...")
  (server/start-repl-server println)
  (server/on-ready #(execute-repl repl-env verbose warn-on-undeclared)))
