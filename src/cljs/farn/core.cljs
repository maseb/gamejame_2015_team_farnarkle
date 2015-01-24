(ns farn.core
  (:require
            [farn.gfx :as gfx]
            [farn.font :as font]
            [farn.sprite :as sprite]
            [farn.spatial :as spatial]
            [farn.events :as events]
            [farn.store :as store]
            [farn.assets :as assets]
            [farn.query-string :as query-string]
            [farn.utils :refer [log rand-between ends-with?]]
            [cljs.core.async :refer [put! chan <! >! alts! timeout close!]]
            [dommy.core :as dommy :refer-macros [sel1]])
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
)

(enable-console-print!)

(def url (query-string/parse-url (.-href (.-location js/document))))

(println "url-parsed:" url)

(def grass-green 0x357564)

(defonce fonts
  [
   (font/install-google-font-stylesheet! "http://fonts.googleapis.com/css?family=Lobster")
   (font/install-google-font-stylesheet! "http://fonts.googleapis.com/css?family=Varela+Round")
   (font/install-google-font-stylesheet! "http://fonts.googleapis.com/css?family=Shadows+Into+Light+Two")])

(defn load [s urls & {:keys [fade-in fade-out]
                      :or {fade-in 0.5 fade-out 0.5}
                      :as options}]
  (let [c (chan)
        b (gfx/add-prog-bar s options)]
    (go
      ;; fade in
      (<! (gfx/fadein b :duration fade-in))

      ;; show load progress
      (log "load" (str options))
      (<! (apply (partial gfx/load-urls urls b) options))

      ;; load is done. return message
      (>! c true)

      ;; delay a tiny bit
      (<! (timeout 300))

      ;; fadeout
      (<! (gfx/fadeout b :duration fade-out))

      ;; remove progress bar sprite
      (.removeChild s b)

      (close! c))
    c))


(def world (gfx/init :background grass-green
                     :expand true
                     :engine :auto
                     :canvas (sel1 :#game)))
(def main-stage (:world world))
(def ui-stage (:ui world))

;; render every frame
(go
  (while true
    (<! (events/next-frame))
    (.render (:renderer world) (:stage world))))

(defn make-sprite [tex]
  (let [s (gfx/make-sprite tex)]
    (sprite/set-anchor! s 0.5 1)
    s))

(def cell-size 1000)

(if (:test (:query-params url))
  (do
    ; visit a URL with ?test=1 or &test=1 in there somewhere
    (println "Testing code goes here!")

  )

; main live code goes here
(go
  (let [loader
        (load ui-stage assets/=assets=
              :full-colour 0x306020
              :highlight 0x80ff80
              :lowlight 0x103010
              :empty-colour 0x000000
              ;; :debug-delay 0.1
              :fade-in 0.2
              :fade-out 2)]
    (log "pulling")
    (<! loader)
    (log "pulled" (str assets/=assets=))
    (let [
          lobster-big (font/make-tiled-font "Lobster" 400 40)
          title-text (font/font-make-batch lobster-big "Alien Forest Explorer" )
          tex (gfx/get-texture :pink-stand-4)
          player (make-sprite tex)
          _ (log "GUY" player)
          trees (for [i (range 10)]
                  (gfx/get-texture (keyword (str "static-tree-" (inc i)))))
          tufts (for [i (range 3)]
                  (gfx/get-texture (keyword (str "static-tuft-" (inc i)))))

          depth-compare (fn [a b]
                          ;(log "comp" (.-position.y a) (.-position.y b))
                          (cond
                           (< (.-position.y a) (.-position.y b)) -1
                           (< (.-position.y b) (.-position.y a)) 1
                           :default 0))

          game-map (spatial/make-random-map
                       [:static-tree-1
                        :static-tree-2
                        :static-tree-3
                        :static-tree-4
                        :static-tree-5
                        :static-tree-6
                        :static-tree-7
                        :static-tree-8
                        :static-tree-9
                        :static-tree-10
                        :static-tuft-1
                        :static-tuft-2
                        :static-tuft-3
                        ] 5000 -5000 5000 -5000 5000)

          game-sprites (doall (for [obj game-map]
                                (assoc obj
                                  :sprite (make-sprite (gfx/get-texture (:type obj)))
                                  :scale 0.5
                                  )))

          game-space (spatial/hash-locations game-sprites cell-size)

          add-cell! (fn [id]
                     (doseq [obj (game-space id)]
                       (log "adding" (str obj))
                       (sprite/set-scale! (:sprite obj) (:scale obj))

                       ;; add them way off so they don't pop
                       ;; TODO: fix this
                       (sprite/set-pos! (:sprite obj) 100000 100000)

                       (.addChild main-stage (:sprite obj))))

          remove-cell! (fn [id]
                         (doseq [obj (game-space id)]
                           (log "removing" (str obj))
                           (.removeChild main-stage (:sprite obj)))
                         )
]
                                        ;(.addChild ui-stage (:sprite title-text))
      (<! (timeout 1000))
      (log "adding")
      (doto player
        (sprite/set-scale! 0.5))
      (.addChild main-stage player)

      (add-cell! (spatial/which-cell player-pos cell-size))

      (.sort (.-children main-stage) depth-compare)

      (loop [pos [0 0] theta 0 cells #{[0 0]}
             ]
        (let [[x y] pos
              calc-theta (+ theta Math/PI)

              ;; his heading unit vector
              hx (Math/cos calc-theta)
              hy (Math/sin calc-theta)

              ;; the reverse heading vector
              rhx (Math/cos (- (* 2 Math/PI) calc-theta))
              rhy (Math/sin (- (* 2 Math/PI) calc-theta))

              speed 4

              vx (* speed hx)
              vy (* speed hy)

              ;; what cell is player in
              player-cell (spatial/which-cell pos cell-size)

              ;; if the present cell isn't in cells, lets load it in
              new-cells (if-not (some #(= % player-cell) cells)
                          (do
                            (add-cell! player-cell)
                            (conj cells player-cell)
                            )

                          cells)

              ;; remove cells that are too far away from player
              cull-cells
              (filter #(not (nil? %))
                      (for [c cells]
                        (let [[cx cy] c
                              [px py] player-cell
                              dx (Math/abs (- cx px))
                              dy (Math/abs (- cy py))
                              d-squared (+ (* dx dx) (* dy dy))
                              ]
                          (if (> d-squared 3)
                            c
                            nil))))

              post-remove-cells (loop [[h & t] cull-cells
                                  final-cells new-cells]
                             (if-not (nil? h)
                               (do
                                 ;; remove the cell
                                 (remove-cell! h)
                                 (recur t (disj final-cells h)))

                               ;; exit. return the new cell list
                               final-cells
                               ))
              ;_ (log (str cull-cells))
                                        ;(log "pos" (str pos) "theta" theta)
]

          (doto player
            (sprite/set-pos! pos))


          ;; set the static world sprites to the correct orientation (rotate trees)
          (doseq [cell cells]
            (doseq [obj (game-space cell)]
              ;(println "->" obj)
              (let [
                    ;; absolute tree location
                    [ox oy] (:pos obj)

                    ;; vector from player to tree
                    p->t [(- ox x) (- oy y)]
                    [p->t.x p->t.y] p->t

                    ;; rotate this p->t
                    [rx ry] [
                             (+ (* rhx p->t.y) (* rhy p->t.x))
                             (- (* rhx p->t.x) (* rhy p->t.y))
                             ]

                    ;; now add to the the player loc
                    fx (+ rx x)
                    fy (+ ry y)
                    ]
                (doto (:sprite obj)
                  (sprite/set-pos!
                   fx fy
                   )))))

          ;; move cetnter of render to be on player
          (sprite/set-pivot! main-stage x y)

          (.sort (.-children main-stage) depth-compare)
          (<! (events/next-frame))
          (recur
           ;; new position
           (if (events/is-pressed? :down)
             [(+ x vx) (+ y vy)]
             (if (events/is-pressed? :up)
               [(- x vx) (- y vy)]
               [x y]))

           ;; new heading
           (if (events/is-pressed? :left)
             (+ theta 0.03)
             (if (events/is-pressed? :right)
               (- theta 0.03)
               theta))

           ;; pass through new cell list
           post-remove-cells
           )))

                                        ;(.removeChild ui-stage (:sprite title-text))
      ))))

(defn main []
)
