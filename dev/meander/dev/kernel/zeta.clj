(ns meander.dev.kernel.zeta
  (:require [clojure.walk :as walk]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [meander.epsilon :as me]
            [meander.syntax.epsilon :as me.syntax]
            [meander.match.syntax.epsilon :as me.match.syntax]
            [meander.substitute.syntax.epsilon :as me.subst.syntax]
            [meander.runtime.zeta :as m.runtime]))

(defn defmetafn-doc-string
  {:private true}
  [symbol args]
  (format "Pattern matching and substitution operator.

  When used as a pattern matching opeator, it attempts to match the
  form

     ['%s %s]

  When used as a pattern substitution operator, it constructs a shape
  of the same form accepted by match and wrapped in
  `meander.epsilon/cata`.

     (meander.epsilon/cata ['%s %s]"
          (str symbol)
          (clojure.string/join " " args)
          (str symbol)
          (clojure.string/join " " args)))

(defmacro defmetafn
  "Defines a pattern matching and substitution operator.

  When the defined operator is used as a pattern matching opeator,
  it attempts to match the form

     ['ns/name ~@args]

  When the defined operator is used as a pattern substitution operator,
  it constructs a shape of the same form accepted by match and wrapped
  in `meander.epsilon/cata`.

     (meander.epsilon/cata ['ns/name ~@args])"
  [name args]
  (let [fq-sym (symbol (str (ns-name *ns*)) (str name))
        name (vary-meta name assoc :doc (defmetafn-doc-string fq-sym args))]
    `(me/defsyntax ~name [~@args]
       (let [shape# ['~fq-sym ~@args]]
         (cond
           (me/match-syntax? ~'&env)
           shape#

           (me/subst-syntax? ~'&env)
           (me/cata shape#))))))

(defn epsilon->zeta [form]
  (walk/prewalk
   (fn [x]
     (me/rewrite x
       meander.substitute.runtime.epsilon/fail?
       meander.runtime.zeta/fail?

       meander.match.runtime.epsilon/partitions
       meander.runtime.zeta/epsilon-partitions

       meander.match.runtime.epsilon/fail?
       meander.runtime.zeta/fail?

       meander.match.runtime.epsilon/FAIL
       (meander.runtime.zeta/fail)

       meander.match.runtime.epsilon/run-star-1
       meander.runtime.zeta/epsilon-run-star-1

       meander.substitute.runtime.epsilon/FAIL
       (meander.runtime.zeta/fail)

       meander.substitute.runtime.epsilon/iterator
       meander.runtime.zeta/iterator

       meander.substitute.runtime.epsilon/iterator-seq
       clojure.core/iterator-seq

       ?x ?x))
   form))

;; This may not be needed any loger.
(defn replace-$-variables [form]
  (let [replace (memoize (fn [s] (gensym (str s "__"))))]
    (walk/postwalk
     (fn [x]
       (me/match x
         (me/symbol nil (me/re #"\$.+" ?name))
         (replace ?name)

         ?x ?x))
     form)))


(defn check [left-form right-form]
  (let [left-ast (me.match.syntax/parse left-form)
        right-ast (me.subst.syntax/parse right-form)
        left-vars (me.syntax/variables left-ast)
        right-vars (me.syntax/variables right-ast)
        vars-missing-on-left (set/difference right-vars left-vars)]
    (if (seq vars-missing-on-left)
      {:message "There are variables on the right which do not appear on the left."
       :data {:vars-missing-on-left (into #{} (map :symbol) vars-missing-on-left)
              :left-form left-form
              :left-meta (meta left-form)
              :right-form right-form
              :right-meta (meta right-form)}})))

;; This should probably be folded back into epsilon.
(defmacro check-rules! [rules-list-expr]
  `(doseq [[left-form# right-form#] (partition-all 2 ~rules-list-expr)]
     (if-some [{message# :message data# :data} (check left-form# right-form#)]
       ;; It would be awesome if we could modify the stack trace to
       ;; point to the line number given by, for example, :right-meta
       ;; (if one exists).
       (throw (ex-info message# data#)))))

(defmacro defmodule [module-name & rules]
  (check-rules! rules)
  (let [input-symbol (gensym "input__")
        rewrite-form (macroexpand (case (:type (meta &form))
                                    :match
                                    `(me/match ~input-symbol ~@rules)
                                    ;; else
                                    `(me/rewrite ~input-symbol ~@rules)))
        rewrite-form (epsilon->zeta rewrite-form)
        rewrite-form (replace-$-variables rewrite-form)
        defn-form `(defn ~module-name [~input-symbol] ~rewrite-form)
        ns (symbol (str "meander.compiled." module-name ".zeta"))
        ns-form (list 'ns ns '(:require [meander.runtime.zeta]
                                        [meander.util.zeta]))
        file (io/file "src/meander/compiled" (munge (name module-name))  "zeta.clj")
        parent (io/file (.getParent file))]
    (.mkdirs parent)
    (.createNewFile file)
    (with-open [file (io/writer file)]
      (binding [*out* file]
        (pprint/pprint ns-form)
        (pprint/pprint defn-form)))
    (require ns :reload)
    (let [meta (meta module-name)
          meta (assoc meta :arglists (get meta :arglists ''([input])))]
      `(def ~(with-meta module-name meta)
         (var ~(symbol (str ns) (str module-name)))))))
