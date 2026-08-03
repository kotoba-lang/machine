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
