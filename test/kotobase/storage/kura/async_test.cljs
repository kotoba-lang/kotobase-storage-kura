(ns kotobase.storage.kura.async-test
  (:require [ipld.car.bytes :as car-bytes]
            [kotobase.storage.core :as storage]
            [kotobase.storage.kura.async :as sut]
            [kotobase.storage.memory :as catalog-memory]
            [kura.node.async :as shard-async]
            [kura.node.gf :as gf]
            [kura.node.store :as node-store]
            [multiformats.core :as mf])
  (:require-macros [cljs.test :refer [deftest is async]]))

(defrecord FakeAsyncStore [id state descriptor]
  shard-async/IAsyncShardStore
  (-put-shard!> [_ shard-id bytes]
    (swap! state assoc-in [:shards shard-id] bytes)
    (js/Promise.resolve {:shard-id shard-id
                         :bytes-written (car-bytes/bcount bytes)}))
  (-get-shard> [_ shard-id]
    (swap! state update :full-reads conj shard-id)
    (js/Promise.resolve
     (when-not (contains? (:unavailable @state) shard-id)
       (get-in @state [:shards shard-id]))))
  (-get-range> [_ shard-id offset length]
    (swap! state update :range-reads conj [shard-id offset length])
    (js/Promise.resolve
     (when-not (contains? (:unavailable @state) shard-id)
       (when-let [bytes (get-in @state [:shards shard-id])]
         (let [end (min (car-bytes/bcount bytes) (+ offset length))]
           (when (< offset end)
             (car-bytes/slice bytes offset end)))))))
  (-delete-shard!> [_ shard-id]
    (let [present? (contains? (:shards @state) shard-id)]
      (swap! state update :shards dissoc shard-id)
      (js/Promise.resolve present?)))
  (-list-shards> [_ prefix]
    (js/Promise.resolve
     (->> (:shards @state)
          keys
          (filter #(.startsWith % prefix))
          sort
          vec)))
  (-shard-size> [_ shard-id]
    (js/Promise.resolve
     (when-let [bytes (get-in @state [:shards shard-id])]
       (car-bytes/bcount bytes))))

  node-store/INodeIdentity
  (-descriptor [_] descriptor))

(defn- fake-store [id]
  (let [descriptor (node-store/descriptor
                    {:node-id id
                     :failure-domain {:machine id}
                     :independence :independent
                     :availability :always-on
                     :capabilities #{:range-read}})]
    (->FakeAsyncStore id
                      (atom {:shards {} :unavailable #{}
                             :range-reads [] :full-reads []})
                      descriptor)))

(defn- backend-fixture []
  (let [ids (mapv #(str "node-" %) (range 7))
        fleet (into {} (map (fn [id] [id (fake-store id)]) ids))
        placement-context {:nodes (mapv (fn [id] {:id id}) ids)}
        backend (sut/open
                 {:fleet fleet
                  :plan-placement
                  (fn [_object-id layout _context]
                    {:complete? true
                     :shortfall 0
                     :shards (zipmap (range (:n layout)) ids)})
                  :placement-context placement-context
                  :catalog (catalog-memory/memory-store)
                  :verify-block (fn [_cid _bytes] true)
                  :max-pack-bytes 1024
                  :max-stripe-bytes 1024
                  :layout {:k 4 :r 2 :g 1}})]
    {:ids ids :fleet fleet :backend backend}))

(defn- block-fixture []
  (let [bytes (gf/->bytes (mapv #(mod (+ (* 17 %) 5) 256) (range 200)))]
    {:cid (mf/cidv1-raw bytes) :bytes bytes}))

(defn- mark-unavailable! [store shard-id]
  (swap! (:state store) update :unavailable conj shard-id))

(deftest missing-data-range-uses-local-parity-before-full-pack-read
  (async done
    (let [{:keys [ids fleet backend]} (backend-fixture)
          {:keys [cid bytes]} (block-fixture)
          missing-store (get fleet (first ids))]
      (-> (storage/-put-blocks! backend [{:cid cid :bytes bytes}])
          (.then (fn [_]
                   (let [shard-id (first (keys (:shards @(:state missing-store))))]
                     (mark-unavailable! missing-store shard-id)
                     (storage/-get-blocks backend [cid]))))
          (.then (fn [blocks]
                   (is (= (gf/->vec bytes)
                          (gf/->vec (get blocks cid))))
                   (is (pos? (count (:range-reads @(:state (get fleet (nth ids 4))))))
                       "local parity range was fetched")
                   (is (zero? (reduce + (map #(count (:full-reads @(:state %)))
                                             (vals fleet))))
                       "successful local recovery avoids full-pack reconstruction")
                   (done)))
          (.catch (fn [error]
                    (is false (str "local parity recovery failed: " error))
                    (done)))))))

(deftest failed-local-parity-range-falls-back-to-digest-checked-pack-read
  (async done
    (let [{:keys [ids fleet backend]} (backend-fixture)
          {:keys [cid bytes]} (block-fixture)
          missing-data-store (get fleet (first ids))
          missing-parity-store (get fleet (nth ids 4))]
      (-> (storage/-put-blocks! backend [{:cid cid :bytes bytes}])
          (.then (fn [_]
                   (let [data-id (first (keys (:shards @(:state missing-data-store))))
                         parity-id (first (keys (:shards @(:state missing-parity-store))))]
                     (mark-unavailable! missing-data-store data-id)
                     (mark-unavailable! missing-parity-store parity-id)
                     (storage/-get-blocks backend [cid]))))
          (.then (fn [blocks]
                   (is (= (gf/->vec bytes)
                          (gf/->vec (get blocks cid))))
                   (is (pos? (reduce + (map #(count (:full-reads @(:state %)))
                                             (vals fleet))))
                       "Kura's full reconstruction is reached after local reads fail")
                   (done)))
          (.catch (fn [error]
                    (is false (str "verified full-pack fallback failed: " error))
                    (done)))))))
