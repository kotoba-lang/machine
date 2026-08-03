(ns machine.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [machine.core :as m]))

(def ^:private probed
  "A two-socket, two-level-cache descriptor used as the positive fixture.
  Numbers are internally consistent, not lifted from any real product."
  {:format m/format-id
   :machine/id "fixture-2-node"
   :machine/provenance :measured
   :machine/source "test fixture"
   :cpu {:arch :x86-64 :cores 16
         :simd {:name :avx2 :width-bits 256}
         :cache [{:level 1 :kind :data :bytes 32768 :line-bytes 64 :ways 8 :shared-by 1}
                 {:level 1 :kind :instruction :bytes 32768 :line-bytes 64 :ways 8 :shared-by 1}
                 {:level 2 :kind :unified :bytes 1048576 :line-bytes 64 :ways 16 :shared-by 1}
                 {:level 3 :kind :unified :bytes 33554432 :line-bytes 128 :ways 16 :shared-by 8}]}
   :page {:base-bytes 4096 :huge [2097152 1073741824]}
   :numa {:nodes 2 :distance [[10 21] [21 10]]}
   :dram {:channels 8 :row-bytes 8192}
   :gpu {:kind :vulkan :max-workgroup 1024 :subgroup 32 :shared-bytes 49152}
   :storage [{:id :nvme0 :kind :nvme :block-bytes 4096 :queue-depth 64
              :seek-cost :none :max-transfer-bytes 131072}
             {:id :spin0 :kind :hdd :block-bytes 4096 :queue-depth 1
              :seek-cost :high :max-transfer-bytes 1048576}]})

(deftest shipped-profiles-are-valid
  (is (m/valid? m/portable-64))
  (is (m/valid? m/unknown))
  (is (m/valid? probed)))

(deftest unknown-carries-no-facts
  (testing "an unprobed machine answers nil rather than a default"
    (is (nil? (m/line-bytes m/unknown)))
    (is (nil? (m/page-bytes m/unknown)))
    (is (nil? (m/gpu m/unknown))))
  (testing "and a planner that needs the number fails loudly"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (m/require-fact m/unknown [:cpu :cache] "a cache hierarchy")))))

(deftest closed-keys
  (testing "a typo'd section is an error, not a silently absent section"
    (let [errs (m/validation-errors (assoc probed :strorage []))]
      (is (some #(= :unknown-key (:error %)) errs))))
  (is (some #(= :missing-required-key (:error %))
            (m/validation-errors (dissoc probed :machine/source))))
  (is (some #(= :invalid-format (:error %))
            (m/validation-errors (assoc probed :format :kotoba.machine/v0)))))

(deftest cache-geometry-is-checked
  (testing "capacity must equal ways * line * sets"
    (is (some #(= :cache-geometry-inconsistent (:error %))
              (m/validation-errors
               (assoc-in probed [:cpu :cache 0 :bytes] 32767)))))
  (testing "a level that shrinks is a transcription error"
    (is (some #(= :cache-capacity-not-increasing (:error %))
              (m/validation-errors
               (assoc-in probed [:cpu :cache 3 :bytes] 65536)))))
  (testing "line width must be a power of two"
    (is (some #(= :invalid-cache-line-bytes (:error %))
              (m/validation-errors
               (assoc-in probed [:cpu :cache 0 :line-bytes] 48))))))

(deftest line-bytes-takes-the-widest
  (testing "L3 has a 128-byte line here; padding to L1's 64 would leave false sharing"
    (is (= 128 (m/line-bytes probed))))
  (is (= 64 (m/line-bytes m/portable-64))))

(deftest private-capacity-divides-by-sharers
  (is (= 33554432 (m/cache-bytes probed 3 :unified)))
  (testing "eight cores share L3, so one core may assume an eighth"
    (is (= 4194304 (m/private-cache-bytes probed 3 :unified))))
  (is (= 1048576 (m/private-cache-bytes probed 2 :unified))))

(deftest cache-at-falls-back-to-unified
  (is (= 32768 (:bytes (m/cache-at probed 1 :data))))
  (testing "L2 is unified, so a :data lookup at L2 resolves to it"
    (is (= 1048576 (:bytes (m/cache-at probed 2 :data))))))

(deftest simd-lanes-are-derived-not-declared
  (is (= 8 (m/simd-lanes probed 4)))
  (is (= 4 (m/simd-lanes probed 8)))
  (is (nil? (m/simd-lanes m/unknown 4))))

(deftest numa-distance-rules
  (is (m/numa-local? probed 0 0))
  (is (not (m/numa-local? probed 0 1)))
  (testing "a node further from itself than from a peer inverts every placement"
    (is (some #(= :numa-self-distance-not-minimal (:error %))
              (m/validation-errors
               (assoc-in probed [:numa :distance] [[30 21] [21 10]])))))
  (testing "the matrix must be square and match the node count"
    (is (some #(= :invalid-numa-distance-matrix (:error %))
              (m/validation-errors (assoc-in probed [:numa :distance] [[10 21]]))))))

(deftest storage-facts
  (is (= :nvme (:kind (m/storage-device probed :nvme0))))
  (is (nil? (m/storage-device probed :missing)))
  (testing "reordering buys nothing on a zero-seek device"
    (is (not (m/reorderable? (m/storage-device probed :nvme0))))
    (is (m/reorderable? (m/storage-device probed :spin0))))
  (testing "two devices may not share an id"
    (is (some #(= :duplicate-storage-id (:error %))
              (m/validation-errors
               (update probed :storage conj
                       {:id :nvme0 :kind :nvme :block-bytes 4096 :queue-depth 8
                        :seek-cost :none :max-transfer-bytes 65536}))))))

(deftest gpu-rules
  (is (some #(= :gpu-workgroup-smaller-than-subgroup (:error %))
            (m/validation-errors (assoc-in probed [:gpu :max-workgroup] 16))))
  (is (some #(= :invalid-gpu-max-workgroup (:error %))
            (m/validation-errors (assoc-in probed [:gpu :max-workgroup] 100)))))

(deftest huge-pages-must-be-larger-than-base
  (is (some #(= :huge-page-not-larger-than-base (:error %))
            (m/validation-errors (assoc-in probed [:page :huge] [1024])))))

(deftest provenance-ordering
  (is (m/at-least-as-strong? :measured :vendor-declared))
  (is (m/at-least-as-strong? :measured :measured))
  (is (not (m/at-least-as-strong? :assumed :vendor-declared)))
  (is (not (m/at-least-as-strong? :vendor-declared :measured)))
  (testing "the shipped floor is assumed, so no claim may lean on it"
    (is (= :assumed (:machine/provenance m/portable-64)))
    (is (not (m/at-least-as-strong? (:machine/provenance m/portable-64) :measured)))))

(deftest measured-demands-evidence
  (let [stamped (m/measured m/portable-64 "sysctl hw.cachelinesize")]
    (is (= :measured (:machine/provenance stamped)))
    (is (= "sysctl hw.cachelinesize" (:machine/source stamped))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/measured m/portable-64 ""))))

(deftest fingerprint-is-deterministic-and-sensitive
  (is (= (m/fingerprint probed) (m/fingerprint probed)))
  (testing "key order in the source map cannot change the fingerprint"
    (is (= (m/fingerprint probed)
           (m/fingerprint (into (sorted-map) probed)))))
  (testing "one changed number changes it"
    (is (not= (m/fingerprint probed)
              (m/fingerprint (assoc-in probed [:cpu :cores] 15)))))
  (testing "it stays inside the declared 31-bit range"
    (is (<= 0 (m/fingerprint probed) 2147483646))))

(deftest validate-throws-with-the-whole-error-list
  (let [broken (-> probed (dissoc :machine/source) (assoc :nonsense 1))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (m/validate! broken)))
    (is (<= 2 (count (m/validation-errors broken))))))

;; ── heterogeneous CPUs (added 2026-08-03 after first contact with an M1 Max) ──

(def ^:private hetero
  {:format m/format-id
   :machine/id "p+e"
   :machine/provenance :measured
   :machine/source "test fixture"
   :cpu {:arch :aarch64 :cores 10
         :simd {:name :neon :width-bits 128}
         :clusters [{:id :performance :cores 8
                     :cache [{:level 1 :kind :data :bytes 131072 :line-bytes 128 :shared-by 1}
                             {:level 2 :kind :unified :bytes 12582912 :line-bytes 128 :shared-by 4}]}
                    {:id :efficiency :cores 2
                     :cache [{:level 1 :kind :data :bytes 65536 :line-bytes 128 :shared-by 1}
                             {:level 2 :kind :unified :bytes 4194304 :line-bytes 128 :shared-by 2}]}]}
   :page {:base-bytes 16384 :huge []}})

(deftest ways-are-optional-because-some-platforms-do-not-report-them
  (testing "macOS exposes sizes, line width and topology but not associativity"
    (is (m/valid? hetero))
    (is (every? #(nil? (:ways %)) (m/caches hetero))))
  (testing "a declared but nonsensical ways is still an error"
    (is (some #(= :invalid-cache-ways (:error %))
              (m/validation-errors (assoc-in hetero [:cpu :clusters 0 :cache 0 :ways] 0))))))

(deftest a-capacity-question-with-no-single-answer-throws
  (is (m/heterogeneous? hetero))
  (is (= [:performance :efficiency] (mapv :id (m/clusters hetero))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/private-cache-bytes hetero 2 :unified)))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (m/cache-at hetero 1 :data)))
  (testing "but a maximum still has one, so line width answers"
    (is (= 128 (m/line-bytes hetero)))))

(deftest for-cluster-hands-back-an-ordinary-flat-descriptor
  (let [p (m/for-cluster hetero :performance)
        e (m/for-cluster hetero :efficiency)]
    (is (m/valid? p))
    (is (not (m/heterogeneous? p)))
    (is (= "p+e/performance" (:machine/id p)))
    (is (= 3145728 (m/private-cache-bytes p 2 :unified)))
    (is (= 2097152 (m/private-cache-bytes e 2 :unified)))
    (testing "the cluster inherits the CPU's SIMD when it declares none"
      (is (= 4 (m/simd-lanes p 4))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (m/for-cluster hetero :nonexistent)))))

(deftest carrying-both-a-flat-cache-and-clusters-is-refused
  (testing "a planner would read the flat one and mis-plan for the other half"
    (is (some #(= :both-flat-cache-and-clusters (:error %))
              (m/validation-errors
               (assoc-in hetero [:cpu :cache]
                         [{:level 1 :kind :data :bytes 32768 :line-bytes 64 :shared-by 1}]))))))

(deftest duplicate-cluster-ids-are-refused
  (is (some #(= :duplicate-cluster-id (:error %))
            (m/validation-errors
             (assoc-in hetero [:cpu :clusters 1 :id] :performance)))))

;; ── bandwidth is machine x access pattern ───────────────────────────────

(def ^:private with-curve
  (assoc probed :bandwidth
         {:by-stride {128 24.1 256 29.7 512 27.6 1024 34.3
                      4096 14.5 16384 11.5 65536 14.2}
          :source "one f64 touched every S bytes over a 256 MiB working set"}))

(deftest a-curve-needs-a-source-like-every-other-measurement
  (is (m/valid? with-curve))
  (is (some #(= :bandwidth-curve-needs-a-source (:error %))
            (m/validation-errors (assoc-in with-curve [:bandwidth :source] ""))))
  (is (some #(= :invalid-bandwidth-curve (:error %))
            (m/validation-errors (assoc-in with-curve [:bandwidth :by-stride] {}))))
  (is (some #(= :invalid-bandwidth-entry (:error %))
            (m/validation-errors (assoc-in with-curve [:bandwidth :by-stride] {128 0})))))

(deftest bandwidth-is-looked-up-by-stride-not-assumed
  (testing "exact measured points"
    (is (= 24.1 (m/bandwidth-at-stride with-curve 128)))
    (is (= 11.5 (m/bandwidth-at-stride with-curve 16384))))
  (testing "between points it takes the nearer-lower stride"
    (is (= 34.3 (m/bandwidth-at-stride with-curve 2048)))
    (is (= 14.5 (m/bandwidth-at-stride with-curve 8192))))
  (testing "past the last measured point it does not extrapolate"
    (is (= 14.2 (m/bandwidth-at-stride with-curve 1048576))))
  (testing "below the first, the smallest measured stride is the closest answer"
    (is (= 24.1 (m/bandwidth-at-stride with-curve 8)))))

(deftest a-machine-without-a-measured-curve-answers-nil
  (testing "the same contract as every other absent fact — ask out loud"
    (is (nil? (m/bandwidth-at-stride probed 4096)))
    (is (nil? (m/bandwidth-at-stride m/unknown 4096)))))

(deftest the-spread-is-why-this-exists
  (testing "3x between the best and worst stride on one machine; picking the
            wrong end made a model predict 1.00x against a measured 2.69x"
    (let [vals (vals (get-in with-curve [:bandwidth :by-stride]))]
      (is (< 2.5 (/ (apply max vals) (apply min vals)))))))
