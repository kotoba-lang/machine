(ns machine.os-stack-tranche1-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [machine.core :as m]))

;; ADR-2809050100 gap-2 tranche 1: the five reference devices (UEFI system
;; partition, NVMe, USB mass storage, 802.11ax, ext4) exist as validated
;; machine descriptors. A device is "supported" only when its descriptor
;; passes validation — these tests keep that true.

(def specs
  (edn/read-string
   (slurp "resources/machine/profiles/os-stack-tranche1.edn")))

(deftest all-descriptors-validate
  (doseq [s specs]
    (is (empty? (m/validation-errors (:descriptor s)))
        (str "invalid descriptor: " (:machine/id s)))))

(deftest machine-ids-are-unique
  (is (= (count specs) (count (set (map :machine/id specs))))))

(deftest provenance-is-never-fabricated
  ;; Nothing here is :measured — no host probe has run. A future tranche
  ;; flips a value to :measured only with a real :machine/source.
  (doseq [s specs
          :let [d (:descriptor s)]]
    (is (contains? #{:vendor-declared :assumed} (:machine/provenance d)))
    (is (seq (:machine/source d)))))

(deftest nvme-is-not-reorderable-but-wifi-is
  ;; The ioplan split the OS stack depends on: elevator sort harmful on
  ;; NVMe, useful on a Wi-Fi link where radio time varies.
  (let [by-id (into {} (map (juxt :machine/id :descriptor) specs))]
    (is (false? (m/reorderable? (m/storage-device (by-id "reference-x86-64-nvme") :nvme0))))
    (is (true? (m/reorderable? (m/storage-device (by-id "reference-x86-64-wifi-11ax") :wifi0))))))

(deftest uefi-partition-block-smaller-than-nvme
  ;; EFI System Partition is 512B logical; NVMe is 4KiB. A boot loader
  ;; planning against 4KiB on the ESP misreads the GPT.
  (let [by-id (into {} (map (juxt :machine/id :descriptor) specs))]
    (is (= 512 (:block-bytes (m/storage-device (by-id "reference-x86-64-uefi") :uefi-system-partition))))
    (is (= 4096 (:block-bytes (m/storage-device (by-id "reference-x86-64-nvme") :nvme0))))))
