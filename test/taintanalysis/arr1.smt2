(set-option :fixedpoint.engine spacer)

(declare-rel inv (Int Bool Bool (Array Int Bool) (Array Int  Bool)))
(declare-rel err ())

(declare-var rng (Array Int Bool))
(declare-var a Int)
(declare-var dt Bool)
(declare-var mt1 Bool)
(declare-var mt2 (Array Int Bool))


(rule (=> (and dt mt1 (forall ((i Int)) (=> (select rng i) (select mt2 i)))) (inv a dt mt1 mt2 rng)))
(rule (=> (and (inv a dt mt1 mt2 rng))
          (inv a (and mt1 (select mt2 a)) mt1 (store mt2 a (select rng a)) rng)))
(rule (=> (and (inv a dt mt1 mt2 rng) (not dt) (select rng a)) err))
(query err :print-certificate true)
