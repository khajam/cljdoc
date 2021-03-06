(ns boot.user)

(def sandbox-analysis-deps
  "This is what is being loaded in the pod that is used for analysis.
  It is also the stuff that we cannot generate documentation for in versions
  other than the ones listed below. (See CONTRIBUTING for details.)"
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/java.classpath "0.2.2"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/clojurescript "1.9.946"] ; Codox depends on old CLJS which fails with CLJ 1.9
    [codox "0.10.3"]])

(def application-deps
  "These are dependencies we can use regardless of what we're analyzing.
  All code using these dependencies does not operate on Clojure source
  files but the Grimoire store and project repo instead."
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/test.check "0.9.0"]
    [com.cognitect/transit-clj "0.8.300"]

    [confetti "0.2.0"]
    [bidi "2.1.3"]
    [hiccup "2.0.0-alpha1"]

    [org.slf4j/slf4j-nop "1.7.25"]
    [org.eclipse.jgit "4.10.0.201712302008-r"]

    [org.clojure-grimoire/lib-grimoire "0.10.9"]
    ;; lib-grimpoire depends on an old core-match
    ;; which pulls in other old stuff
    [org.clojure/core.match "0.3.0-alpha5"]])


(boot.core/set-env! :source-paths #{"src"}
                    :dependencies application-deps)

(require '[boot.pod :as pod]
         '[boot.util :as util]
         '[clojure.java.io :as io]
         '[clojure.spec.alpha :as spec]
         '[cljdoc.git-repo :as gr]
         '[cljdoc.renderers.html]
         '[cljdoc.renderers.transit]
         '[cljdoc.grimoire-helpers]
         '[confetti.boot-confetti :as confetti])

(spec/check-asserts true)

(defn jar-file [coordinate]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(= coordinate (:dep %)))
       (first)
       :jar))

(deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file."]
  (with-pre-wrap fileset
    (let [d (tmp-dir!)]
      (util/info "Unpacking %s\n" jar)
      (pod/unpack-jar jar (io/file d "jar-contents/"))
      (-> fileset (add-resource d) commit!))))

(defn pom-path [project]
  (let [artifact (name project)
        group    (or (namespace project) artifact)]
    (str "META-INF/maven/" group "/" artifact "/pom.xml")))

(defn group-id [project]
  (or (namespace project) (name project)))

(defn artifact-id [project]
  (name project))

(defn docs-path [project version]
  (str "" (group-id project) "/" (artifact-id project) "/" version "/"))

(defn scm-url [pom-map]
  (clojure.string/replace
   (cond (.contains (:url (:scm pom-map)) "github")
         (:url (:scm pom-map))
         (.contains (:url pom-map) "github")
         (:url pom-map))
   #"^http://"
   "https://")) ;; TODO HACK

(defn version-tag? [pom-version tag]
  (or (= pom-version tag)
      (= (str "v" pom-version) tag)))

(defn find-pom-map [fileset project]
  (when-let [pom (some->> (output-files fileset)
                          (by-path [(str "jar-contents/" (pom-path project))])
                          first
                          tmp-file)]
    ;; TODO assert that only one pom.xml is found
    (pod/with-eval-in pod/worker-pod
      (require 'boot.pom)
      (boot.pom/pom-xml-parse-string ~(slurp pom)))))

(deftask import-repo
  "Scans the fileset for a pom.xml for the specified project,
   detects the referenced Github/SCM repo and clones it into
   a git-repo/ subdirectory in the fileset."
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [pom-map (find-pom-map fs project)
          tempd   (tmp-dir!)
          git-dir (io/file tempd "git-repo")]
      (when-not pom-map
        (util/warn "Could not find pom.xml for %s in fileset\n" project))
      (if-let [scm (scm-url pom-map)]
        (do (util/info "Identified project repository %s\n" scm)
            (.mkdir git-dir)
            (gr/clone scm git-dir)
            (let [repo (gr/->repo git-dir)]
              (if-let [version-tag (->> (gr/git-tag-names repo)
                                        (filter #(version-tag? version %))
                                        first)]
                (gr/git-checkout-repo repo version-tag)
                (util/warn "No version tag found for version %s in %s\n" version scm))))
        (util/warn "Could not determine project repository for %s\n" project))
      (-> fs (add-resource tempd) commit!))))

(defn boot-tmpd-containing [fs re-ptn]
  (->> (output-files fs) (by-re [re-ptn]) first :dir))

(defn jar-contents-dir [fileset]
  (some-> (boot-tmpd-containing fileset #"^jar-contents/")
          (io/file "jar-contents")))

(defn git-repo-dir [fileset]
  (some-> (boot-tmpd-containing fileset #"^git-repo/")
          (io/file "git-repo")))

(deftask codox
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd     (tmp-dir!)
          pom-map   (find-pom-map fs project)
          codox-dir (io/file tempd "codox-docs/")
          cdx-pod (pod/make-pod {:dependencies (into sandbox-analysis-deps
                                                     [[project version]
                                                      '[codox-theme-rdash "0.1.2"]])})]
      (util/info "Generating codox documentation for %s\n" project)
      (let [docs-dir (-> (boot-tmpd-containing fs #"^jar-contents/")
                         (io/file "git-repo" "doc")
                         (.getPath))]
        (boot.util/dbug "Codox doc-paths %s\n" [docs-dir])
        (pod/with-eval-in cdx-pod
          (require 'codox.main)
          (boot.util/dbug "Codox pod env: %s\n" boot.pod/env)
          (->> {:name         ~(name project)
                :version      ~version
                ;; It seems :project is only intended for overrides
                ;; :project      {:name ~(name project), :version ~version, :description ~(:description pom-map)}
                :description  ~(:description pom-map)
                :source-paths [~(.getPath (jar-contents-dir fs))]
                :output-path  ~(.getPath codox-dir)
                ;; Codox' way of determining :source-uri is tricky since it depends working on
                ;; the repository while we are not giving it the repository information but jar-contents
                ;; :source-uri   ~(str (scm-url pom-map) "/blob/{version}/{filepath}#L{line}")
                :doc-paths    [~docs-dir]
                :language     nil
                :namespaces   nil
                :metadata     nil
                :writer       nil
                :exclude-vars nil
                :themes       [:rdash]}
               (remove (comp nil? second))
               (into {})
               (codox.main/generate-docs))))
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (boot.util/info "Creating analysis pod ...\n")
    (let [tempd        (tmp-dir!)
          grimoire-dir (io/file tempd "grimoire")
          grimoire-pod (pod/make-pod {:dependencies (conj sandbox-analysis-deps [project version])
                                      :directories #{"src"}})
          build-cdx      (fn [jar-contents-path platf]
                           (pod/with-eval-in grimoire-pod
                             (require 'cljdoc.analysis)
                             (cljdoc.analysis/codox-namespaces ~jar-contents-path ~platf)))
          cdx-namespaces {"clj"  (build-cdx (.getPath (jar-contents-dir fs)) "clj")
                          "cljs" (build-cdx (.getPath (jar-contents-dir fs)) "cljs")}]
      (util/info "Generating Grimoire store for %s\n" project)
      (doseq [platf ["clj" "cljs"]]
        (cljdoc.grimoire-helpers/build-grim
         {:group-id     (group-id project)
          :artifact-id  (artifact-id project)
          :version      version
          :platform     platf}
         (get cdx-namespaces platf)
         {:dst      (.getPath grimoire-dir)
          :git-repo (gr/->repo (git-repo-dir fs))}))
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire-html
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-dir      (let [boot-dir (->> (output-files fs) (by-re [#"^grimoire/"]) first :dir)]
                              (assert boot-dir)
                              (io/file boot-dir "grimoire/"))
          grimoire-html-dir (io/file tempd "grimoire-html")
          grimoire-thing    (-> (grimoire.things/->Group (group-id project))
                                (grimoire.things/->Artifact (artifact-id project))
                                (grimoire.things/->Version version))
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire HTML for %s\n" project)
      (.mkdir grimoire-html-dir)
      (require 'cljdoc.renderers.html 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.renderers.html/->HTMLRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir grimoire-html-dir})
      (-> fs (add-resource tempd) commit!))))

(deftask transit-cache
  [p project PROJECT sym "Project to build transit cache for"
   v version VERSION str "Version of project to build transit cache for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-dir      (let [boot-dir (->> (output-files fs) (by-re [#"^grimoire/"]) first :dir)]
                              (assert boot-dir)
                              (io/file boot-dir "grimoire/"))
          grimoire-thing    (-> (grimoire.things/->Group (group-id project))
                                (grimoire.things/->Artifact (artifact-id project))
                                (grimoire.things/->Version version))
          transit-cache-dir (io/file tempd "transit-cache")
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire Transit cache for %s\n" project)
      (.mkdir transit-cache-dir)
      (require 'cljdoc.renderers.transit 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.renderers.transit/->TransitRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir transit-cache-dir})
      (-> fs (add-resource tempd) commit!))))

(defn open-uri [format-str project version]
  (let [uri (format format-str (group-id project) (artifact-id project) version)]
    (boot.util/info "Opening %s\n" uri)
    (boot.util/dosh "open" uri)))

(deftask open
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pass-thru _ (open-uri "http://localhost:5000/%s/%s/%s/" project version)))

(deftask deploy-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (let [doc-path (docs-path project version)]
    (assert (.endsWith doc-path "/"))
    (assert (not (.startsWith doc-path "/")))
    (comp (sift :include #{#"^grimoire-html"})
          (sift :move {#"^grimoire-html/(.*)" (str "$1")})
          ;; TODO find common prefix of all files in the fileset, pass as :invalidation-paths
          ;; TODO remove all uses of `docs-path`
          (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"
                                ;; also invalidates root path (also cheaper)
                                :invalidation-paths [(str "/" doc-path "*")])
          (with-pass-thru _
            (let [base-uri "https://cljdoc.martinklepsch.org"]
              (util/info "\nDocumentation can be viewed at:\n\n    %s/%s\n\n" base-uri doc-path))))))

(defmacro when-task [test task-form]
  `(if ~test ~task-form identity))

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   d deploy          bool "Also deploy newly built docs to S3?"
   c run-codox       bool "Also generate codox documentation"]
  (comp (copy-jar-contents :jar (jar-file [project version]))
        (import-repo :project project :version version)
        (grimoire :project project :version version)
        (grimoire-html :project project :version version)
        (transit-cache :project project :version version)
        (when-task deploy
          (deploy-docs :project project :version version))
        (when-task run-codox
          (codox :project project :version version))
        #_(open :project project :version version)))

(deftask wipe-s3-bucket []
  (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"
                        :prune true))

(deftask update-site []
  (set-env! :resource-paths #{"site"})
  (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"))

(deftask analysis-deps []
  (set-env! :dependencies sandbox-analysis-deps)
  identity)

(comment
  (def f
    (future
      (boot (watch) (build-docs :project 'bidi :version "2.1.3"))))

  (future-cancel f)

  )
