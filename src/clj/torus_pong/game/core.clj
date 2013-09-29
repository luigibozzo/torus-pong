(ns torus-pong.game.core
  (:require [torus-pong.game.params :as params]))

(defn initial-field-state
  [id]
  {:player {:position (/ params/game-height 2)
            :id id
            :score 0}
   :balls [{:p {:x  (int (rand params/game-width))
                :y  (int (rand params/game-height))}
            :v  {:x 1 :y 1}}]})

(def initial-game-state
  {:fields []})

(defn find-field-index
  [game-state id]
  (some (fn [[idx v]] (if (= v id) idx))
        (map-indexed (fn [ix v] [ix (-> v :player :id)]) (:fields game-state))))

(defn move-up
  [position]
  (if (< (+ position (/ params/paddle-height 2)) params/game-height)
    (reduce min [(+ position params/distance-paddle-moves-per-tick) params/game-height])
    position))

(defn move-down
  [position]
  (if (> (- position (/ params/paddle-height 2)) 0)
    (reduce max [(- position params/distance-paddle-moves-per-tick) 0])
    position))

(defmulti handle-command
  (fn [game-state command]
    (first command)))

(defmethod handle-command :player/leave
  [game-state [command id]]
  (assoc game-state :fields
         (vec (remove #(= (-> % :player :id) id) (:fields game-state)))))

(defmethod handle-command :player/join
  [game-state [command id]]
  (update-in game-state [:fields] conj (initial-field-state id)))

(defmethod handle-command :player/up
  [game-state [command id]]
  (let [field-index (find-field-index game-state id)]
    (update-in game-state [:fields field-index :player :position] move-up)))

(defmethod handle-command :player/down
  [game-state [command id]]
  (let [field-index (find-field-index game-state id)]
    (update-in game-state [:fields field-index :player :position] move-down)))

(defn handle-commands
  [game-state commands]
  (reduce (fn [current-state command]
            (handle-command current-state command))
          game-state commands))

(defn abs
  [i]
  (if (< 0 i) i
      (- i)))

(defn player-collision?
  [ball player]
  (let [x-distance (abs (- (/ params/game-width 2) (-> ball :p :x)))
        y-distance (abs (- (player :position) (-> ball :p :y)))]
    (and (< x-distance (+ (/ params/paddle-width 2) params/ball-radius))
         (< y-distance (+ (/ params/paddle-height 2) params/ball-radius)))))

(defn invert-velocity
  [ball]
  (update-in ball [:v :x] -))

(defn player-collision
  [ball player]
  (if (player-collision? ball player)
    (invert-velocity ball)
    ball))

(defn wall-collision?
  [ball]
  (or (> (+ params/ball-radius (-> ball :p :y))
         params/game-height)
      (< (-> ball :p :y) params/ball-radius)))

(defn wall-collision
  [ball]
  (if (wall-collision? ball)
    (update-in ball [:v :y] -)
    ball))

(defn ball-collision?
  [first-ball second-ball]
  ;;check to make sure they are not the same ball
  (and (not= first-ball second-ball)
       (< (abs (- (-> first-ball :p :x) (-> second-ball :p :x)))
          (* 2 params/ball-radius))
       (< (abs (- (-> first-ball :p :y) (-> second-ball :p :y)))
          (* 2 params/ball-radius))))

(defn ball-collision
  [ball balls]
  (if (some #(ball-collision? ball %) balls)
    (-> ball
        (update-in [:v :y] -)
        (update-in [:v :x] -))
    ball))

(defn apply-velocity
  [ball]
  (let [current-x-pos (-> ball :p :x)
        current-y-pos (-> ball :p :y)
        current-x-vel (-> ball :v :x)
        current-y-vel (-> ball :v :y)]
    {:p {:x (+ current-x-pos (* params/ball-speed current-x-vel))
         :y (+ current-y-pos (* params/ball-speed current-y-vel))}
     :v {:x current-x-vel
         :y current-y-vel}}))

(defn safe-ball-from-player
  [ball player]
  (if (player-collision? ball player)
    (if (< (-> ball :p :x) (/ params/game-width 2))
      (assoc-in ball [:p :x] (- (/ params/game-width 2)
                                params/ball-radius
                                (/ params/paddle-width 2)))
      (assoc-in ball [:p :x] (+ (/ params/game-width 2)
                                params/ball-radius
                                (/ params/paddle-width 2))))
    ball))

(defn constraint
  [lo hi val]
  (max (min val hi) lo))

(defn safe-ball-from-wall
  [ball]
  (if (wall-collision? ball)
    (update-in ball [:p :y] (partial constraint 0 params/game-height))
    ball))

(defn safe-ball
  "Removes ball from walls and players"
  [ball player]
  (-> ball
      (safe-ball-from-player player)
      safe-ball-from-wall))

(defn advance-ball
  [field ball]
  (let [balls  (:ball field)
        player (:player field)]
    (-> ball
        (player-collision player)
        wall-collision
        (ball-collision balls)
        (safe-ball player)
        apply-velocity)))

(defn count-player-collisions
  [player balls]
  (reduce (fn [agg ball] (if (player-collision? ball player) (inc agg) agg)) 0 balls))

(defn advance-score-on-hits
  [field]
  (update-in field [:player :score] (partial + (count-player-collisions (:player field) (:balls field))) ))

(defmulti apply-scoring
  (fn [game-state]
    params/scoring))

(defmethod apply-scoring :hits
  [game-state]
  (update-in game-state [:fields] (partial mapv advance-score-on-hits)))

(defn advance-field
  [field]
  (update-in field [:balls] (partial mapv (partial advance-ball field))))

(defn advance-fields
  [game-state]
  (update-in game-state [:fields] (partial mapv advance-field)))

;; Moving balls between fields

(defn outside-left?
  [ball]
  (< (-> ball :p :x) 0))

(defn outside-right?
  [ball]
  (> (-> ball :p :x) params/game-width))

(defn outside?
  [ball]
  (or (outside-left? ball) (outside-right? ball)))

(defn update-ball-field-partition
  [[left-field field right-field]]
  (let [safe-balls (remove outside? (:balls field))
        new-left-balls (filter outside-right? (:balls left-field))
        new-right-balls (filter outside-left? (:balls right-field))]
    (assoc field
      :balls
      (vec
       (concat
        (map (fn [ball] (assoc-in ball [:p :x] (- (-> ball :p :x) params/game-width)))
             new-left-balls)
        safe-balls
        (map (fn [ball] (assoc-in ball [:p :x] (+ (-> ball :p :x) params/game-width)))
             new-right-balls))))))

(defn update-ball-fields
  [game-state]
  (let [fields (:fields game-state)
        field-partitions (partition
                          3 1 (concat
                               [(last fields)] fields [(first fields)]))]
    (assoc game-state
      :fields
      (mapv update-ball-field-partition field-partitions))))

(defn advance
  "Given a game-state and some inputs, advance the game-state one
  tick"
  [game-state commands]
  (let [new-game-state (-> game-state
                           (handle-commands commands)
                           (apply-scoring)
                           (advance-fields)
                           update-ball-fields)]
    new-game-state))
