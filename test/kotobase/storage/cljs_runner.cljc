(ns kotobase.storage.cljs-runner
  "  clojure -M:cljs -m cljs.main --target node -m kotobase.storage.cljs-runner"
  (:require [clojure.test :as t :refer [run-tests]]
            [kotobase.storage.kura-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main [] (run-tests 'kotobase.storage.kura-test))
