/*
 * UCLID5 Verification and Synthesis Engine
 *
 * Copyright (c) 2017.
 * Sanjit A. Seshia, Rohit Sinha and Pramod Subramanyan.
 *
 * All Rights Reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 *
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Author: Pramod Subramanyan
 *
 * Z3 Solver for Constrained Horn Clauses.
 *
 */

package uclid
package smt

import com.microsoft.z3
import com.typesafe.scalalogging.Logger
import uclid.lang._

import scala.collection.mutable.ArrayBuffer



case class TransitionSystem(
    init : smt.Expr,
    tr   : smt.Expr,
    inv  : smt.Symbol,
    bads : Seq[smt.Expr]
)

class Z3HornSolver(sim: SymbolicSimulator) extends Z3Interface {
  z3.Global.setParameter("fp.engine", "spacer")
  type TaintSymbolTable = Map[Identifier, List[smt.Expr]]
  type TaintFrameTable = ArrayBuffer[TaintSymbolTable]
  type RefinementTable = Map[AssertInfo, smt.Expr]

  val log = Logger(classOf[Z3HornSolver])
  var id = 0
  /*
  var boundIdx = 0
  var boundHyperIdx = 0
  var symbolIdx = 0

  /*val exprToZ3Horn : Memo[Expr, z3.AST] = new Memo[Expr, z3.AST](
    e =>
        exprToZ3(e)
  )*/

  val hornSymbolMap : Memo[smt.Symbol, z3.Symbol] = new Memo[smt.Symbol, z3.Symbol](
    e =>
      //symbolIdx += 1
      ctx.mkSymbol(symbolIdx)
  )
  var hornBoundSymbolMap : HashMap[Expr, z3.Expr] = new HashMap[Expr, z3.Expr]

  def addZ3Symbols(syms :List[smt.Symbol]) = {
    syms.foreach {
      sym =>
        hornSymbolMap.memoHashMap.get(sym) match {
          case Some(x) =>
          case None =>
            hornSymbolMap(sym)
            symbolIdx += 1
        }

    }
  }

  def addBoundVariables(syms: List[smt.Symbol], startIdx: Option[Int], isHyper: Boolean): Unit = {

    if (!isHyper) {
      startIdx match {
        case Some(i) => boundIdx = i
        case None =>
      }
      syms.foreach {
        sym =>
          hornBoundSymbolMap += (sym -> ctx.mkBound(boundIdx, getZ3Sort(sym.typ)))
          exprToZ3Horn.memoHashMap += (sym -> ctx.mkBound(boundIdx, getZ3Sort(sym.typ)))
          boundIdx += 1
      }
    }
    else {

      startIdx match {
        case Some(i) => boundHyperIdx = i
        case None =>
      }
      syms.foreach {
        sym =>
          hornBoundSymbolMap += (sym -> ctx.mkBound(boundHyperIdx, getZ3Sort(sym.typ)))
          exprToZ3Horn.memoHashMap += (sym -> ctx.mkBound(boundHyperIdx, getZ3Sort(sym.typ)))
          val v = exprToZ3Horn(sym)
          UclidMain.println("Bound sym " + sym + " to boundVar " + v.toString)
          boundHyperIdx += 1
      }
    }
  }

  def clearExprMap() = {
    exprToZ3Horn.memoHashMap.clear()
  }

  def opToZ3Horn(op : Operator, operands : List[Expr]) : z3.Expr  = {
    lazy val args = operands.map((arg) => exprToZ3Horn(arg))
    // These values need to be lazy so that they are only evaluated when the appropriate ctx.mk* functions
    // are called. If they were eager, the casts would fail at runtime.
    lazy val exprArgs = typecastAST[z3.Expr](args)
    lazy val arithArgs = typecastAST[z3.ArithExpr](args)
    lazy val boolArgs = typecastAST[z3.BoolExpr](args)
    lazy val bvArgs = typecastAST[z3.BitVecExpr](args)

    def mkReplace(w : Int, hi : Int, lo : Int, arg0 : z3.BitVecExpr, arg1 : z3.BitVecExpr) : z3.BitVecExpr = {
      val slice0 = (w-1, hi+1)
      val slice2 = (lo-1, 0)

      // Convert a valid slice into Some(bvexpr) and an invalid slice into none.
      def getSlice(slice : (Int, Int), arg : z3.BitVecExpr) : Option[z3.BitVecExpr] = {
        if (slice._1 >= slice._2) {
          Utils.assert(slice._1 < w && slice._1 >= 0, "Invalid slice: " + slice.toString)
          Utils.assert(slice._2 < w && slice._2 >= 0, "Invalid slice: " + slice.toString)
          Some(ctx.mkExtract(slice._1, slice._2, arg))
        } else {
          None
        }
      }
      // Now merge the slices.
      val slices : List[z3.BitVecExpr] = List(getSlice(slice0, arg0), Some(arg1), getSlice(slice2, arg0)).flatten
      val repl = slices.tail.foldLeftjazz autumn leaves(slices.head)((r0, r1) => ctx.mkConcat(r0, r1))
      Utils.assert(w == repl.getSortSize(), "Invalid result size.")
      return repl
    }
    op match {
      case IntLTOp                => ctx.mkLt (arithArgs(0), arithArgs(1))
      case IntLEOp                => ctx.mkLe (arithArgs(0), arithArgs(1))
      case IntGTOp                => ctx.mkGt (arithArgs(0), arithArgs(1))
      case IntGEOp                => ctx.mkGe (arithArgs(0), arithArgs(1))
      case IntAddOp               => ctx.mkAdd (arithArgs : _*)
      case IntSubOp               =>
        if (args.size == 1) {
          ctx.mkUnaryMinus(arithArgs(0))
        } else {
          ctx.mkSub (arithArgs: _*)
        }
      case IntMulOp               => ctx.mkMul (arithArgs : _*)
      case BVLTOp(_)              => ctx.mkBVSLT(bvArgs(0), bvArgs(1))
      case BVLEOp(_)              => ctx.mkBVSLE(bvArgs(0), bvArgs(1))
      case BVGTOp(_)              => ctx.mkBVSGT(bvArgs(0), bvArgs(1))
      case BVGEOp(_)              => ctx.mkBVSGE(bvArgs(0), bvArgs(1))
      case BVAddOp(_)             => ctx.mkBVAdd(bvArgs(0), bvArgs(1))
      case BVSubOp(_)             => ctx.mkBVSub(bvArgs(0), bvArgs(1))
      case BVMulOp(_)             => ctx.mkBVMul(bvArgs(0), bvArgs(1))
      case BVMinusOp(_)           => ctx.mkBVNeg(bvArgs(0))
      case BVAndOp(_)             => ctx.mkBVAND(bvArgs(0), bvArgs(1))
      case BVOrOp(_)              => ctx.mkBVOR(bvArgs(0), bvArgs(1))
      case BVXorOp(_)             => ctx.mkBVXOR(bvArgs(0), bvArgs(1))
      case BVNotOp(_)             => ctx.mkBVNot(bvArgs(0))
      case BVExtractOp(hi, lo)    => ctx.mkExtract(hi, lo, bvArgs(0))
      case BVConcatOp(w)          => ctx.mkConcat(bvArgs(0), bvArgs(1))
      case BVReplaceOp(w, hi, lo) => mkReplace(w, hi, lo, bvArgs(0), bvArgs(1))
      case BVSignExtOp(w, e)      => ctx.mkSignExt(e, bvArgs(0))
      case BVZeroExtOp(w, e)      => ctx.mkZeroExt(e, bvArgs(0))
      case BVLeftShiftOp(w, e)    => ctx.mkBVSHL(bvArgs(0), ctx.mkBV(e, w))
      case BVLRightShiftOp(w, e)  => ctx.mkBVLSHR(bvArgs(0), ctx.mkBV(e, w))
      case BVARightShiftOp(w, e)  => ctx.mkBVASHR(bvArgs(0), ctx.mkBV(e, w))
      case NegationOp             => ctx.mkNot (boolArgs(0))
      case IffOp                  => ctx.mkIff (boolArgs(0), boolArgs(1))
      case ImplicationOp          => ctx.mkImplies (boolArgs(0), boolArgs(1))
      case EqualityOp             => ctx.mkEq(exprArgs(0), exprArgs(1))
      case InequalityOp           => ctx.mkDistinct(exprArgs(0), exprArgs(1))
      case ConjunctionOp          => ctx.mkAnd (boolArgs : _*)
      case DisjunctionOp          => ctx.mkOr (boolArgs : _*)
      case ITEOp                  => ctx.mkITE(exprArgs(0).asInstanceOf[z3.BoolExpr], exprArgs(1), exprArgs(2))
      case ForallOp(vs)           =>
        // val qTyps = vs.map((v) => getZ3Sort(v.typ)).toArray
        val qVars = vs.map((v) => symbolToZ3(v).asInstanceOf[z3.Expr]).toArray
        ctx.mkForall(qVars, boolArgs(0), 1, null, null, getForallName(), getSkolemName())
      case ExistsOp(vs)           =>
        val qVars = vs.map((v) => symbolToZ3(v).asInstanceOf[z3.Expr]).toArray
        ctx.mkExists(qVars, boolArgs(0), 1, null, null, getExistsName(), getSkolemName())
      case RecordSelectOp(fld)    =>
        val prodType = operands(0).typ.asInstanceOf[ProductType]
        val fieldIndex = prodType.fieldIndex(fld)
        val prodSort = getProductSort(prodType)
        prodSort.getFieldDecls()(fieldIndex).apply(exprArgs(0))
      case RecordUpdateOp(fld) =>
        val prodType = operands(0).typ.asInstanceOf[ProductType]
        val fieldIndex = prodType.fieldIndex(fld)
        val indices = prodType.fieldIndices
        val prodSort = getProductSort(prodType)
        val newFields = indices.map{ (i) =>
          if (i == fieldIndex) exprArgs(1)
          else prodSort.getFieldDecls()(i).apply(exprArgs(0))
        }
        prodSort.mkDecl().apply(newFields.toSeq : _*)
      case _             => throw new Utils.UnimplementedException("Operator not yet implemented: " + op.toString())
    }
  }

  val getConstArrayHorn = new Memo[(Expr, ArrayType), z3.ArrayExpr]({
    (p) => {
      val value = exprToZ3Horn(p._1).asInstanceOf[z3.Expr]
      val sort = getArrayIndexSort(p._2.inTypes)
      val arr = ctx.mkConstArray(sort, value)
      arr
    }
  })

  val exprToZ3Horn : Memo[Expr, z3.AST] = new Memo[Expr, z3.AST]((e) => {
    def toArrayIndex(index : List[Expr], indexType : List[Type]) : z3.Expr = {
      if (index.size == 1) {
        exprToZ3Horn(index(0)).asInstanceOf[z3.Expr]
      } else {
        getTuple(index.map((arg) => exprToZ3Horn(arg)), indexType)
      }
    }

    val z3AST : z3.AST = e match {
      case Symbol(id, typ) =>
        //symbolToZ3(Symbol(id, typ))
        //throw new Utils.UnimplementedException("Map should never reach here.")
        hornBoundSymbolMap(Symbol(id, typ))
      case OperatorApplication(op,operands) =>
        opToZ3Horn(op, operands)
      case ArraySelectOperation(e, index) =>
        val arrayType = e.typ.asInstanceOf[ArrayType]
        val arrayIndexType = arrayType.inTypes
        val arrayIndex = toArrayIndex(index, arrayIndexType)
        ctx.mkSelect(exprToZ3Horn(e).asInstanceOf[z3.ArrayExpr], arrayIndex)
      case ArrayStoreOperation(e, index, value) =>
        val arrayType = e.typ.asInstanceOf[ArrayType]
        val arrayIndexType = arrayType.inTypes
        val arrayIndex = toArrayIndex(index, arrayIndexType)
        val data = exprToZ3Horn(value).asInstanceOf[z3.Expr]
        ctx.mkStore(exprToZ3Horn(e).asInstanceOf[z3.ArrayExpr], arrayIndex, data)
      case FunctionApplication(e, args) =>
        UclidMain.println("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^")
        val func = exprToZ3Horn(e).asInstanceOf[z3.FuncDecl]
        func.apply(typecastAST[z3.Expr](args.map(exprToZ3Horn(_))).toSeq : _*)
      case Lambda(_,_) =>
        throw new Utils.RuntimeError("Lambdas in assertions should have been beta-reduced.")
      case IntLit(i) => getIntLit(i)
      case BitVectorLit(bv,w) => getBitVectorLit(bv, w)
      case BooleanLit(b) => getBoolLit(b)
      case EnumLit(e, typ) => getEnumLit(e, typ)
      case ConstArray(expr, typ) => getConstArrayHorn(expr, typ)
      case MakeTuple(args) =>
        val tupleSort = getTupleSort(args.map(_.typ))
        tupleSort.mkDecl().apply(typecastAST[z3.Expr](args.map(exprToZ3Horn(_))).toSeq : _*)
    }
    // z3AST
    if (z3AST.isInstanceOf[z3.Expr]) z3AST.asInstanceOf[z3.Expr].simplify()
    else z3AST
  }) */

  def getUniqueId() = {
    id = id + 1
    id - 1
  }

  def hornSolve(M : TransitionSystem) : List[Option[Boolean]] = {
    List.empty
  }


  def solveLambdas(initLambda: smt.Lambda, nextLambda: smt.Lambda,
                   initHyperAssumes: List[smt.Expr], initAsserts: List[AssertInfo],
                   initHyperAsserts: List[AssertInfo], nextHyperAsserts: List[AssertInfo],
                   nextHyperAssumes: List[smt.Expr], nextAsserts: List[AssertInfo],
                   scope: Scope) = {

    z3.Global.setParameter("fp.engine", "spacer")
    val sorts = initLambda.ids.map(id => getZ3Sort(id.typ))
    var finalSorts = sorts
    val numCopies = sim.getMaxHyperInvariant(scope)
    for (i <- 1 to numCopies - 1) {
      finalSorts = finalSorts ++ sorts
    }

    def applyDecl(f : z3.FuncDecl, args: List[z3.Expr]) : z3.BoolExpr = {
      Predef.assert(f.getDomainSize() == args.length)
      /*f.getDomain.zip(args).foreach {
        d =>
          UclidMain.println("domain typ and arg typ" + (d._1, d._2.getSort))
      }
      UclidMain.println("The args to applyDecl " + args.toString)*/
      f.apply(args : _*).asInstanceOf[z3.BoolExpr]
    }

    var qId = 0
    var skId = 0

    /*
    def createForall(sorts : Array[z3.Sort], symbols : Array[z3.Symbol], e : z3.Expr) = {
      qId += 1
      skId += 1
      Predef.assert(sorts.length == symbols.length)
      ctx.mkForall(sorts, symbols, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }*/

    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    val simRecord = new sim.SimulationTable
    var prevVarTable = new ArrayBuffer[List[List[smt.Expr]]]()
    var havocTable = new ArrayBuffer[List[(smt.Symbol, smt.Symbol)]]()

    //val boolSort = ctx.mkBoolSort()
    //val invSingle = ctx.mkFuncDecl("invSingle", sorts.toArray, boolSort)
    val invHyperDecl = ctx.mkFuncDecl("invHyper", finalSorts.toArray, boolSort)
    val fp = ctx.mkFixedpoint()
    fp.registerRelation(invHyperDecl)
    //fp.registerRelation(invSingle)

    var inputStep = 0
    var initConjuncts = new ArrayBuffer[smt.Expr]()
    var nextConjuncts = new ArrayBuffer[smt.Expr]()

    for (i <- 1 to numCopies) {
      var frames = new sim.FrameTable
      val initSymTab = sim.newInputSymbols(sim.getInitSymbolTable(scope), inputStep, scope)
      inputStep += 1
      frames += initSymTab
      var prevVars = sim.getVarsInOrder(initSymTab.map(_.swap), scope)
      prevVarTable += prevVars
      val init_havocs = sim.getHavocs(initLambda.e)
      val havoc_subs = init_havocs.map {
        havoc =>
          val s = havoc.id.split("_")
          val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
          (havoc, sim.newHavocSymbol(name, havoc.typ))
      }
      havocTable += havoc_subs
      val init_conjunct = sim.substitute(sim.betaSubstitution(initLambda, prevVars.flatten), havoc_subs)
      //addAssumptionToTree(init_conjunct, List.empty)
      initConjuncts += init_conjunct
      simRecord += frames
    }

    //val initSymbolsSmt = sim.getVarsInOrder(simRecord(0)(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])

    //addZ3Symbols(initSymbolsSmt)
    //addBoundVariables(initSymbolsSmt, Some(0), false)

    //val initSymbolsZ3 = initSymbolsSmt.map(sym => hornSymbolMap(sym))


    //val initSymbolsBound = initSymbolsSmt.
    //  map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val initHyperSymbolsSmt = simRecord.flatMap {
      frameTable =>
        sim.getVarsInOrder(frameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    }.toList

    //UclidMain.println("xxx initHyperSymbolsSmt xx " + initHyperSymbolsSmt.toString)
    //addZ3Symbols(initHyperSymbolsSmt)
    //addBoundVariables(initHyperSymbolsSmt, Some(0), true)

    //val initHyperSymbolsZ3 = initHyperSymbolsSmt.map(sym => hornSymbolMap(sym))

    val initHyperSymbolsBound = initHyperSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    //UclidMain.println("--- initHyperSymbolsbound -- " + initHyperSymbolsBound.toString)
    //UclidMain.println("The Expr Map " + exprToZ3.memoHashMap.toString)
    val initConjunctsZ3 = initConjuncts.map(exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr])
    val rewrittenInitHyperAssumes = sim.rewriteHyperAssumes(
      initLambda, 0, initHyperAssumes, simRecord, 0, scope, prevVarTable.toList)
    log.debug("The rewritten hyperAssumes " + rewrittenInitHyperAssumes.toString())
    val hypAssumesInitZ3 = rewrittenInitHyperAssumes.map(exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr])


    //UclidMain.println(initSymbolsZ3.toString)
    //UclidMain.println(initSymbolsBound.toString)
    /*sorts.foreach {
      sort =>
        UclidMain.println("Some sort = " + sort.toString)
    }*/
    //val initRule = createForall(initSymbolsBound.toArray, ctx.mkImplies(initConjunctsZ3(0),
    //  applyDecl(invSingle, initSymbolsBound)))

    val initHyperRule = createForall(initHyperSymbolsBound.toArray, ctx.mkImplies(ctx.mkAnd(initConjunctsZ3 ++ hypAssumesInitZ3 : _*),
      applyDecl(invHyperDecl, initHyperSymbolsBound)))
    log.debug("The Init Conjunct " + initConjunctsZ3(0))
    //fp.addRule(initRule, ctx.mkSymbol("initRule"))
    //UclidMain.println("---FPFPFPFPFPFPF--- The Fixed point " + fp.toString())
    fp.addRule(initHyperRule, ctx.mkSymbol("initHyperRule"))


    val asserts_init = sim.rewriteAsserts(
      initLambda, initAsserts, 0,
      prevVarTable(0).flatten.map(p => p.asInstanceOf[smt.Symbol]),
      simRecord.clone(), havocTable(0))

    val initAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_init.map {
      assert =>
        log.debug("The assert " + assert.expr.toString)
        val errorDecl = ctx.mkFuncDecl("error_init_assert_" + getUniqueId().toString, Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)
        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }

        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        val and = ctx.mkAnd(initConjunctsZ3(0), not, applyDecl(invHyperDecl, initHyperSymbolsBound))
        val rule = createForall(initHyperSymbolsBound.toArray, ctx.mkImplies(and, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
        fp.addRule(rule, ctx.mkSymbol("init_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap


    val asserts_init_hyper = sim.rewriteHyperAsserts(
      initLambda, 0, initHyperAsserts, simRecord, 0, scope, prevVarTable.toList)

    /*
    val initHyperAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_init_hyper.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_init_hyp_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        val pcExpr = assert.pathCond

        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        //val not = ctx.mkNot(exprToZ3(assert.expr).asInstanceOf[z3.BoolExpr])
        val and = ctx.mkAnd(applyDecl(invHyperDecl, initHyperSymbolsBound), ctx.mkAnd(initConjunctsZ3 ++ hypAssumesInitZ3 : _*))
        val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall(initHyperSymbolsBound.toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("init_hyp_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap */


    var symTabStep = sim.symbolTable
    for (j <- 1 to numCopies) {
      symTabStep = sim.newInputSymbols(sim.getInitSymbolTable(scope), inputStep, scope)
      inputStep += 1
      simRecord(j - 1) += symTabStep
      val new_vars = sim.getVarsInOrder(symTabStep.map(_.swap), scope)
      val next_havocs = sim.getHavocs(nextLambda.e)
      val havoc_subs = next_havocs.map {
        havoc =>
          val s = havoc.id.split("_")
          val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
          (havoc, sim.newHavocSymbol(name, havoc.typ))
      }
      val next_conjunct = sim.substitute(sim.betaSubstitution(nextLambda, (prevVarTable(j - 1) ++ new_vars).flatten), havoc_subs)
      //addAssumptionToTree(next_conjunct, List.empty)
      nextConjuncts += next_conjunct

      havocTable(j - 1) = havoc_subs
      prevVarTable(j - 1) = new_vars
    }

    val nextSymbolsSmt = sim.getVarsInOrder(simRecord(0)(1).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    //addZ3Symbols(nextSymbolsSmt)
    //addBoundVariables(nextSymbolsSmt, None, false)

    //val nextSymbolsZ3 = nextSymbolsSmt.map(sym => hornSymbolMap(sym))
    val nextSymbolsBound = nextSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val nextHyperSymbolsSmt = simRecord.flatMap {
      frameTable =>
        sim.getVarsInOrder(frameTable(1).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    }.toList

    // This needs to be computed using the previous bounds
    val nextConjunctInitZ3 = exprToZ3(nextConjuncts(0)).asInstanceOf[z3.BoolExpr]
    //val nextRule = createForall((initSymbolsBound ++ nextSymbolsBound).toArray,
    //  ctx.mkImplies(ctx.mkAnd(nextConjunctInitZ3, applyDecl(invSingle, initSymbolsBound)), applyDecl(invSingle, nextSymbolsBound))
    //)
    //fp.addRule(nextRule, ctx.mkSymbol("nextRule"))

    val asserts_next = sim.rewriteAsserts(
      nextLambda, nextAsserts, 1,
      sim.getVarsInOrder(simRecord(0)(1 - 1).map(_.swap), scope).flatten.map(p => p.asInstanceOf[smt.Symbol]) ++
        prevVarTable(0).flatten.map(p => p.asInstanceOf[smt.Symbol]), simRecord.clone(), havocTable(0))
    //UclidMain.println("Expr Map before Assertions " + exprToZ3.memoHashMap.toString)

    val nextConjunctsZ3 = nextConjuncts.map(expr =>
      exprToZ3(expr).asInstanceOf[z3.BoolExpr])
    //val nextHyperSymbolsZ3 = nextHyperSymbolsSmt.map(sym => hornSymbolMap(sym))
    val nextHyperSymbolsBound = nextHyperSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val nextAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_next.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_next_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)
        log.debug("Error Next: " + assert.expr.toString)
        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        val and = ctx.mkAnd(nextConjunctInitZ3, applyDecl(invHyperDecl, initHyperSymbolsBound), applyDecl(invHyperDecl, nextHyperSymbolsBound))//ctx.mkAnd(nextConjunctsZ3(0), initConjunctsZ3(0))
        val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall((initHyperSymbolsBound ++ nextHyperSymbolsBound).toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("next_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap


    //addZ3Symbols(nextHyperSymbolsSmt)
    // Needed to flush out old subexpressions
    //clearExprMap()
    //addBoundVariables(nextHyperSymbolsSmt, None, true)

    //UclidMain.println("Expr Map Updated " + exprToZ3Horn.memoHashMap.toString)

    log.debug("" + nextHyperSymbolsBound.toString)
    log.debug("Init bound HyperSymbols " + initHyperSymbolsBound.toString)
    log.debug("Next Conjuncts " + nextConjunctsZ3.toString)



    val hyperAssumesNextZ3 = sim.rewriteHyperAssumes(nextLambda, 1,
      nextHyperAssumes, simRecord, 1, scope, prevVarTable.toList).map {
      exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr]
    }

    log.debug("The next HyperAssumes " + nextHyperAssumes.toString)
    log.debug("The next HyperAssumesZ3 " + hyperAssumesNextZ3.toString)


    val nextHyperRule = createForall((initHyperSymbolsBound ++ nextHyperSymbolsBound).toArray,
      ctx.mkImplies(ctx.mkAnd(applyDecl(invHyperDecl, initHyperSymbolsBound), ctx.mkAnd(nextConjunctsZ3 ++ hyperAssumesNextZ3 : _*)),
        applyDecl(invHyperDecl, nextHyperSymbolsBound)
      )
    )
    fp.addRule(nextHyperRule, ctx.mkSymbol("nextHyperRule"))

    val asserts_next_hyper = sim.rewriteHyperAsserts(nextLambda, 1, nextHyperAsserts, simRecord, 1, scope, prevVarTable.toList)
    val nextHyperAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_next_hyper.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_next_hyp_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        //UclidMain.println("Not in hyperAssert " + not.toString)
        //val not = ctx.mkNot(exprToZ3(assert.expr).asInstanceOf[z3.BoolExpr])
        val and = applyDecl(invHyperDecl, nextHyperSymbolsBound)
        val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall(nextHyperSymbolsBound.toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("next_hyp_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap


    log.debug(fp.toString())

    UclidMain.println("Querying Init Asserts")
    initAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }

    /*UclidMain.println("Querying Init Hyper Asserts")
    initHyperAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }*/

    UclidMain.println("Querying Next Asserts")
    nextAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }

    UclidMain.println("Querying Next Hyper Asserts")
    nextHyperAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        val rfp = fp.query(Array(p._2))
        log.debug(rfp.toString())
        if (rfp == z3.Status.SATISFIABLE) {
          //val cex = fp.getAnswer()
          UclidMain.println("SATISFIABLE")
          //UclidMain.println("CEX : " + cex.toString())
        }
        else {
          UclidMain.println(rfp.toString())
        }
        //log.debug("Args: " + fp.getAnswer().getArgs())
        //UclidMain.println(fp.query(Array(p._2)).toString)

    }
    // 1) Make copies of all the variables
    // 2) Make error decls for each of the asserts and hyperAsserts
    // 3) Encode everything like the example
    // 4) For each error relation query the fixed point


  }

  def getNewTaintSymbol(sym: smt.Symbol) = {
    sym.typ match {
      case smt.ArrayType(inTypes, _) =>
        val taint1 = sim.newTaintSymbol(sym.id, smt.BoolType)
        val taint2 = sim.newTaintSymbol(sym.id, smt.ArrayType(inTypes, smt.BoolType))
        List(taint1, taint2)
      case _ => List(sim.newTaintSymbol(sym.id, smt.BoolType))
    }
  }

  def getTaintSymbolTable(scope : Scope, isSimple: Boolean) : TaintSymbolTable = {
    scope.map.foldLeft(Map.empty[Identifier, List[smt.Expr]]){
      (mapAcc, decl) => {
        decl._2 match {
          case Scope.ConstantVar(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newConstantSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.Function(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newConstantSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.EnumIdentifier(id, typ) =>
            val value = if (!isSimple) List(smt.EnumLit(id.name, smt.EnumType(typ.ids.map(_.toString)))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.InputVar(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newInitSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.OutputVar(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newInitSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.StateVar(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newInitSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case Scope.SharedVar(id, typ) =>
            val value = if (!isSimple) getNewTaintSymbol(sim.newInitSymbol(id.name, smt.Converter.typeToSMT(typ))) else List(sim.newTaintSymbol("taint_var_" + id.name, BoolType))
            mapAcc + (id -> value)
          case _ => mapAcc
        }
      }
    }
  }

  def newTaintInputSymbols(st : TaintSymbolTable, step : Int, isSimple: Boolean, scope : Scope) : TaintSymbolTable = {
    scope.map.foldLeft(st)((acc, decl) => {
      decl._2 match {
        case Scope.InputVar(id, typ) =>
          val value = if (!isSimple) sim.newInputSymbol(id.name, step, smt.Converter.typeToSMT(typ)) else List(sim.newTaintSymbol(id.name, BoolType))
          acc + (id -> getNewTaintSymbol(sim.newInputSymbol(id.name, step, smt.Converter.typeToSMT(typ))))
        case _ => acc
      }
    })
  }

  def getTaintVarsInOrder(map: Map[List[smt.Expr], Identifier], scope: Scope) : List[List[smt.Expr]] = {
    val ids = map.map(p => p._2).toList
    val reverse_map = map.map(_.swap)
    val const_vars = ids.filter(id => scope.get(id).get match {
      case Scope.ConstantVar(id, typ) => true
      case _ => false
    }).flatMap(id => reverse_map(id)).sortBy{
      v =>
        val s = v.toString.split("_")

        val name = s.takeRight(s.length - 5).foldLeft(s(4))((acc, p) => acc + "_" + p)
        name
    }
    /*val func_vars = ids.filter(id => scope.get(id).get match {
      case Scope.Function(id, typ) => true
      case _ => false
    }).map(id => reverse_map.get(id).get)
    */
    val input_vars = ids.filter(id => scope.get(id).get match {
      case Scope.InputVar(id, typ) => true
      case _ => false
    }).flatMap(id => reverse_map(id)).sortBy{
      v =>
        val s = v.toString.split("_")
        val name = s.takeRight(s.length - 5).foldLeft(s(4))((acc, p) => acc + "_" + p)
        name
    }
    val output_vars = ids.filter(id => scope.get(id).get match {
      case Scope.OutputVar(id, typ) => true
      case _ => false
    }).flatMap(id => reverse_map(id)).sortBy{
      v =>
        val s = v.toString.split("_")
        val name = s.takeRight(s.length - 5).foldLeft(s(4))((acc, p) => acc + "_" + p)
        name
    }
    val state_vars = ids.filter(id => scope.get(id).get match {
      case Scope.StateVar(id, typ) => true
      case _ => false
    }).flatMap(id => reverse_map(id)).sortBy{
      v =>
        val s = v.toString.split("_")
        val name = s.takeRight(s.length - 5).foldLeft(s(4))((acc, p) => acc + "_" + p)
        name
    }
    val shared_vars = ids.filter(id => scope.get(id).get match {
      case Scope.SharedVar(id, typ) => true
      case _ => false
    }).flatMap(id => reverse_map(id)).sortBy{
      v =>
        val s = v.toString.split("_")
        val name = s.takeRight(s.length - 5).foldLeft(s(4))((acc, p) => acc + "_" + p)
        name
    }
    List(const_vars, input_vars, output_vars, state_vars, shared_vars)

  }

  def lazyScTaint(initLambda: smt.Lambda, nextLambda: smt.Lambda, combinedInitLambda: smt.Lambda, combinedNextLambda: smt.Lambda,
                  initHyperAssumes: List[smt.Expr], nextHyperAssumes: List[smt.Expr], hyperAsserts: List[AssertInfo], scope: Scope, bounded: Boolean,
                  bound: Int) = {
    var refinementTable = hyperAsserts.foldLeft(Map.empty[AssertInfo, smt.Expr]) {
      (mapAcc, assert) =>
        mapAcc + (assert -> smt.BooleanLit(true))
    }

    def noLTLFilter(name : Identifier, decorators : List[ExprDecorator]) : Boolean = !ExprDecorator.isLTLProperty(decorators)
    for (i <- 1 to bound) {
      var fp = constructFixedPoint(initLambda, nextLambda, initHyperAssumes, nextHyperAssumes, scope)
      //var taintPropTable = constructTaintPropTable(fp, hyperAsserts, refinementTable)
      hyperAsserts.zipWithIndex.foreach {
        hypassert =>
          /*val rfp = fp.query(Array[z3.FuncDecl](taintPropTable.get(hypassert._1).get))
          if (rfp == z3.Status.SATISFIABLE) {
            val cex = fp.getAnswer()
            var lazySc = new LazySCSolver(sim)
            var solver = new Z3Interface
            sim.symbolicSimulateLambdasHyperAssert(0, cex.getNumArgs(), hypassert._2, true, false, scope, "SelfComp", noLTLFilter, solver)

            //log.debug("CEX : " + cex.toString())
            //log.debug("Args: " + fp.getAnswer().getArgs())

          }*/
      }


    }

  }

  def constructFixedPointV2(combinedInitLambda: smt.Lambda, combinedNextLambda: smt.Lambda, hyperAsserts: List[AssertInfo], nextTaintVars: Map[smt.Expr, List[smt.Symbol]], nextLambda: smt.Lambda, scope: Scope) = {
    z3.Global.setParameter("fp.engine", "spacer")
    val sorts = combinedInitLambda.ids.map(id => getZ3Sort(id.typ)) ++ List(ctx.mkIntSort())
    val stepVar0 = sim.newStateSymbol("stepVar0", 0, smt.IntType)
    val stepVar1 = sim.newStateSymbol("stepVar1", 1, smt.IntType)

    def applyDecl(f : z3.FuncDecl, args: List[z3.Expr]) : z3.BoolExpr = {
      Predef.assert(f.getDomainSize() == args.length)
      /*f.getDomain.zip(args).foreach {
        d =>
          UclidMain.println("domain typ and arg typ" + (d._1, d._2.getSort))
      }
      UclidMain.println("The args to applyDecl " + args.toString)*/
      f.apply(args : _*).asInstanceOf[z3.BoolExpr]
    }

    var qId = 0
    var skId = 0


    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    var stepOffset = 0
    val simRecord = new sim.SimulationTable
    val frameTable = new sim.FrameTable
    val taintFrameTable = new TaintFrameTable
    var prevVarTable = new ArrayBuffer[List[List[smt.Expr]]]()
    var havocTable = new ArrayBuffer[List[(smt.Symbol, smt.Symbol)]]()
    var prevTaintVarTable = new ArrayBuffer[List[smt.Expr]]()
    var taintAssumes = new ArrayBuffer[smt.Expr]()

    val fp = ctx.mkFixedpoint()
    val invTaintDecl = ctx.mkFuncDecl("invTaintV2", sorts.toArray , boolSort)
    fp.registerRelation(invTaintDecl)


    val taintInitSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, false), stepOffset, false, scope)
    stepOffset += 1
    taintFrameTable += taintInitSymTab
    val initTaintVars = getTaintVarsInOrder(taintInitSymTab.map(_.swap), scope)
    prevTaintVarTable += initTaintVars.flatten

    val initSymTab = sim.newInputSymbols(sim.getInitSymbolTable(scope), stepOffset, scope)
    stepOffset += 1
    frameTable += initSymTab
    var prevVars = sim.getVarsInOrder(initSymTab.map(_.swap), scope)
    prevVarTable += prevVars
    simRecord += frameTable

    val init_havocs = sim.getHavocs(combinedInitLambda.e)
    val havoc_subs = init_havocs.map {
      havoc =>
        val s = havoc.id.split("_")
        val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
        (havoc, sim.newHavocSymbol(name, havoc.typ))
    }
    havocTable += havoc_subs
    val init_conjunct = sim.substitute(sim.betaSubstitution(combinedInitLambda, prevVars.flatten ++ initTaintVars.flatten), havoc_subs)
    //addAssumptionToTree(init_conjunct, List.empty)
    val stepVar0Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar0, smt.IntLit(0)))
    val initConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(init_conjunct, stepVar0Eq))

    log.debug("++++++++ initConjunctFinal ++++++++\n" + initConjunctFinal.toString)

    val initTaintSymbolsSmt = getTaintVarsInOrder(taintFrameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol]) ++ List(stepVar0)
    val initTaintSymbolsBound = initTaintSymbolsSmt.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val initSymbolsSmt = sim.getVarsInOrder(frameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    val initSymbolsBound = initSymbolsSmt.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    val initConjunctZ3 = exprToZ3(initConjunctFinal).asInstanceOf[z3.BoolExpr]

    val initRule = createForall((initSymbolsBound ++ initTaintSymbolsBound).toArray, ctx.mkImplies(initConjunctZ3, applyDecl(invTaintDecl, initSymbolsBound ++ initTaintSymbolsBound)))
    fp.addRule(initRule, ctx.mkSymbol("initRule"))
    //log.debug("+++++++ The V2 Fixed Point +++++++\n" + fp.toString)


    val symTabStep = sim.newInputSymbols(sim.getInitSymbolTable(scope), stepOffset, scope)
    stepOffset += 1
    simRecord(1 - 1) += symTabStep
    val new_vars = sim.getVarsInOrder(symTabStep.map(_.swap), scope)
    val next_havocs = sim.getHavocs(combinedNextLambda.e)
    val havoc_subs_next = next_havocs.map {
      havoc =>
        val s = havoc.id.split("_")
        val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
        (havoc, sim.newHavocSymbol(name, havoc.typ))
    }
    havocTable += havoc_subs_next
    prevVarTable += new_vars
    frameTable += symTabStep

    val taintNextSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, false), stepOffset, false, scope)
    stepOffset += 1
    taintFrameTable += taintNextSymTab
    val nextTaintVars = getTaintVarsInOrder(taintNextSymTab.map(_.swap), scope)
    prevTaintVarTable += nextTaintVars.flatten

    val stepVar1Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar1,
      smt.OperatorApplication(smt.IntAddOp, List(stepVar0, smt.IntLit(1)))))

    //log.debug("Combined Next Lambda " + combinedNextLambda.toString)
    val next_conjunct = sim.substitute(sim.betaSubstitution(combinedNextLambda, (prevVarTable(0).flatten ++ prevTaintVarTable(0)) ++ (prevVarTable(1).flatten ++ prevTaintVarTable(1))), havoc_subs_next)
    //log.debug("THE NEXT CONJUNCT\n" + next_conjunct.toString)
    val nextSymbolsSmt = sim.getVarsInOrder(frameTable(1).map(_.swap), scope).flatten
    val nextSymbolsBound = nextSymbolsSmt.map(smtVar => smtVar.asInstanceOf[smt.Symbol]).map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    val nextTaintSymbolsBound = getTaintVarsInOrder(taintFrameTable(1).map(_.swap), scope).flatten.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr]) ++ List(exprToZ3(stepVar1).asInstanceOf[z3.Expr])
    val nextConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(next_conjunct, stepVar1Eq))

    val nextConjunctZ3 = exprToZ3(nextConjunctFinal).asInstanceOf[z3.BoolExpr]
    val nextRule = createForall((initSymbolsBound ++ nextSymbolsBound ++ initTaintSymbolsBound ++ nextTaintSymbolsBound).toArray,
      ctx.mkImplies(ctx.mkAnd(nextConjunctZ3, applyDecl(invTaintDecl, initSymbolsBound ++ initTaintSymbolsBound)), applyDecl(invTaintDecl, nextSymbolsBound ++ nextTaintSymbolsBound))
    )
    fp.addRule(nextRule, ctx.mkSymbol("nextRule"))
    var nextVarToTaintVarMap = constructVarToTaintVarMap(prevVarTable(1).flatten, prevTaintVarTable(1))
    var nextVarLambdaToNextVarMap = constructVarLambdatoVarMap(nextLambda.ids.takeRight(nextLambda.ids.length / 2), prevVarTable(1).flatten)

    var taintProps = constructTaintPropTable(fp, hyperAsserts, Map.empty, nextVarToTaintVarMap, nextVarLambdaToNextVarMap)
    //log.debug("+++++++ The V2 Fixed Point +++++++\n" + fp.toString)
    //addAssumptionToTree(next_conjunct, List.empty)
    //nextConjuncts += next_conjunct
    fp

  }

  def constructTaintPropTable(fp: z3.Fixedpoint, hyperAsserts: List[AssertInfo], refinementTable: Map[AssertInfo, smt.Expr],
                              nextVarToTaintVarMap: Map[smt.Expr, List[smt.Symbol]], nextVarLambdaToNextVarMap: Map[smt.Expr, smt.Expr]) = {
    // Here each hyperAssert is assumed to be of the following form:
    // f(a, b, c ...) ==> (v1 == v2) and (z1 == z2) and m1[a.1] == m2[a.2]
    // Conversion for this would be:
    // a_t and b_t and c_t .. and f(a1, b1, c1 ...) ==> v_t and z_t and (m_t[a])
    var mapAcc : Map[AssertInfo, z3.FuncDecl] = Map.empty
    hyperAsserts.foreach {
      hypAssert =>
        hypAssert.expr match {
          case smt.OperatorApplication(op, operands) =>
            Predef.assert(op == smt.ImplicationOp, "HyperAssert not in canonical form")
          case _ =>
            Predef.assert(false, "HyperAssert not in canonical form")
        }

        val lhs = hypAssert.expr.asInstanceOf[smt.OperatorApplication].operands(0)
        val rhs = hypAssert.expr.asInstanceOf[smt.OperatorApplication].operands(1)
        val convertedLhs = convertHypAssertLhs(lhs, nextVarToTaintVarMap)
        val convertedRhs = convertHypAssertRhs(rhs)

    }
  }

  def convertHypAssertRhs(rhs: smt.Expr) = {
      // rhs is assumed to be a conjunction of equalities

      def getEqualities(e: smt.Expr): Set[smt.Expr] = {
        e match {
          case opapp: smt.OperatorApplication =>
            val op = opapp.op
            val opers = opapp.operands
            op match {
              case smt.EqualityOp => Set(e)
              case smt.ConjunctionOp => opers.flatMap(o => getEqualities(o)).toSet
              case _ => throw new Utils.UnimplementedException("HyperAssert rhs not in canonical form")
            }
          case _ => throw new Utils.UnimplementedException("HyperAssert rhs not in canonical form")
        }
      }
      val equalities = getEqualities(rhs)
      equalities.map {
        e =>
          val lhs = e.asInstanceOf[smt.OperatorApplication].operands(0)

      }

  }

  def convertHypAssertLhs(lhs: smt.Expr, nextVarToTaintVarMap: Map[smt.Expr, List[smt.Symbol]]) = {
      val supports = getSupports(lhs)
      // Returning only the t1 taints for now
      val supportTaints = supports.map(sym => nextVarToTaintVarMap(sym)(0))
      setToConjunct(supportTaints.toSet)

  }

  def setToConjunct(s: Set[smt.Expr]): smt.Expr = {
      val l = s.toList
      if (l.length > 1) smt.OperatorApplication(smt.ConjunctionOp, l)
      else if (l.length == 1) l(0)
      else smt.BooleanLit(true)

  }

  def getSupports(e: smt.Expr): Set[smt.Symbol] = {
    e match {
      case smt.Symbol(id, symbolTyp) => Set(e.asInstanceOf[smt.Symbol])
      case smt.IntLit(n) => Set()
      case smt.BooleanLit(b) => Set()
      case smt.BitVectorLit(bv, w) => Set()
      case smt.EnumLit(id, eTyp) => Set()
      case smt.ConstArray(v, arrTyp) => Set()
      case smt.MakeTuple(args) => args.flatMap(e => getSupports(e)).toSet
      case opapp : smt.OperatorApplication =>
        val op = opapp.op
        val args = opapp.operands.flatMap(exp => getSupports(exp))
        args.toSet
      //UclidMain.println("Crashing Here" + op.toString)
      case smt.ArraySelectOperation(a,index) =>  getSupports(a) ++ index.flatMap(e => getSupports(e))
      case smt.ArrayStoreOperation(a,index,value) =>
        getSupports(a) ++ index.flatMap(e => getSupports(e)) ++ getSupports(value)
      case smt.FunctionApplication(f, args) =>
        args.flatMap(arg => getSupports(arg)).toSet
      case _ =>
        throw new Utils.UnimplementedException("'" + e + "' is not yet supported.")
    }
  }

  def constructVarLambdatoVarMap(lambdaVars: List[smt.Expr], vars: List[smt.Expr]) = {
    Predef.assert(lambdaVars.length == vars.length)
    lambdaVars.zip(vars).foldLeft(Map.empty[smt.Expr, smt.Expr]) {
      (mapAcc, p) =>
        mapAcc + (p._1 -> p._2)
    }

  }

  def constructVarToTaintVarMap(vars : List[smt.Expr], taintVars: List[smt.Expr]) = {
    Predef.assert(taintVars.length <= 2 * vars.length)
    var mapAcc: Map[smt.Expr, List[smt.Symbol]] = Map.empty
    var i = 0
    vars.foreach {
      v =>
        if (v.typ.isArray) {
          Predef.assert(taintVars(i).isInstanceOf[smt.Symbol])
          Predef.assert(taintVars(i + 1).isInstanceOf[smt.Symbol])
          mapAcc = mapAcc + (v -> List(taintVars(i).asInstanceOf[smt.Symbol], taintVars(i + 1).asInstanceOf[smt.Symbol]))
          i += 2
        }
        else {
          Predef.assert(taintVars(i).isInstanceOf[smt.Symbol])
          mapAcc = mapAcc + (v -> List(taintVars(i).asInstanceOf[smt.Symbol]))
          i += 1
        }
    }
    mapAcc
  }

  def constructFixedPoint(initLambda: smt.Lambda, nextLambda: smt.Lambda, initHyperAssumes: List[smt.Expr], nextHyperAssumes: List[smt.Expr], scope: Scope) = {
    // Here initLambda and nextLambda are the combined versions of the Init and the Next Lambda with the taint lambda
    // The
    // Include the Step Variable
    z3.Global.setParameter("fp.engine", "spacer")
    val sorts = initLambda.ids.map(id => getZ3Sort(id.typ))
    var finalSorts = sorts
    val numCopies = sim.getMaxHyperInvariant(scope)
    for (i <- 1 to numCopies - 1) {
      finalSorts = finalSorts ++ sorts
    }

    def applyDecl(f : z3.FuncDecl, args: List[z3.Expr]) : z3.BoolExpr = {
      Predef.assert(f.getDomainSize() == args.length)
      /*f.getDomain.zip(args).foreach {
        d =>
          UclidMain.println("domain typ and arg typ" + (d._1, d._2.getSort))
      }
      UclidMain.println("The args to applyDecl " + args.toString)*/
      f.apply(args : _*).asInstanceOf[z3.BoolExpr]
    }

    var qId = 0
    var skId = 0

    /*
    def createForall(sorts : Array[z3.Sort], symbols : Array[z3.Symbol], e : z3.Expr) = {
      qId += 1
      skId += 1
      Predef.assert(sorts.length == symbols.length)
      ctx.mkForall(sorts, symbols, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }*/

    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    val simRecord = new sim.SimulationTable
    var prevVarTable = new ArrayBuffer[List[List[smt.Expr]]]()
    var havocTable = new ArrayBuffer[List[(smt.Symbol, smt.Symbol)]]()

    //val boolSort = ctx.mkBoolSort()
    //val invSingle = ctx.mkFuncDecl("invSingle", sorts.toArray, boolSort)
    val invHyperDecl = ctx.mkFuncDecl("invHyper", finalSorts.toArray, boolSort)
    val fp = ctx.mkFixedpoint()
    fp.registerRelation(invHyperDecl)
    //fp.registerRelation(invSingle)

    var inputStep = 0
    var initConjuncts = new ArrayBuffer[smt.Expr]()
    var nextConjuncts = new ArrayBuffer[smt.Expr]()

    for (i <- 1 to numCopies) {
      var frames = new sim.FrameTable
      val initSymTab = sim.newInputSymbols(sim.getInitSymbolTable(scope), inputStep, scope)
      inputStep += 1
      frames += initSymTab
      var prevVars = sim.getVarsInOrder(initSymTab.map(_.swap), scope)
      prevVarTable += prevVars
      val init_havocs = sim.getHavocs(initLambda.e)
      val havoc_subs = init_havocs.map {
        havoc =>
          val s = havoc.id.split("_")
          val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
          (havoc, sim.newHavocSymbol(name, havoc.typ))
      }
      havocTable += havoc_subs
      val init_conjunct = sim.substitute(sim.betaSubstitution(initLambda, prevVars.flatten), havoc_subs)
      //addAssumptionToTree(init_conjunct, List.empty)
      initConjuncts += init_conjunct
      simRecord += frames
    }

    //val initSymbolsSmt = sim.getVarsInOrder(simRecord(0)(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])

    //addZ3Symbols(initSymbolsSmt)
    //addBoundVariables(initSymbolsSmt, Some(0), false)

    //val initSymbolsZ3 = initSymbolsSmt.map(sym => hornSymbolMap(sym))


    //val initSymbolsBound = initSymbolsSmt.
    //  map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val initHyperSymbolsSmt = simRecord.flatMap {
      frameTable =>
        sim.getVarsInOrder(frameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    }.toList

    //UclidMain.println("xxx initHyperSymbolsSmt xx " + initHyperSymbolsSmt.toString)
    //addZ3Symbols(initHyperSymbolsSmt)
    //addBoundVariables(initHyperSymbolsSmt, Some(0), true)

    //val initHyperSymbolsZ3 = initHyperSymbolsSmt.map(sym => hornSymbolMap(sym))

    val initHyperSymbolsBound = initHyperSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    //UclidMain.println("--- initHyperSymbolsbound -- " + initHyperSymbolsBound.toString)
    //UclidMain.println("The Expr Map " + exprToZ3.memoHashMap.toString)
    val initConjunctsZ3 = initConjuncts.map(exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr])
    val rewrittenInitHyperAssumes = sim.rewriteHyperAssumes(
      initLambda, 0, initHyperAssumes, simRecord, 0, scope, prevVarTable.toList)
    log.debug("The rewritten hyperAssumes " + rewrittenInitHyperAssumes.toString())
    val hypAssumesInitZ3 = rewrittenInitHyperAssumes.map(exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr])


    //UclidMain.println(initSymbolsZ3.toString)
    //UclidMain.println(initSymbolsBound.toString)
    /*sorts.foreach {
      sort =>
        UclidMain.println("Some sort = " + sort.toString)
    }*/
    //val initRule = createForall(initSymbolsBound.toArray, ctx.mkImplies(initConjunctsZ3(0),
    //  applyDecl(invSingle, initSymbolsBound)))

    val initHyperRule = createForall(initHyperSymbolsBound.toArray, ctx.mkImplies(ctx.mkAnd(initConjunctsZ3 ++ hypAssumesInitZ3 : _*),
      applyDecl(invHyperDecl, initHyperSymbolsBound)))
    log.debug("The Init Conjunct " + initConjunctsZ3(0))
    //fp.addRule(initRule, ctx.mkSymbol("initRule"))
    //UclidMain.println("---FPFPFPFPFPFPF--- The Fixed point " + fp.toString())
    fp.addRule(initHyperRule, ctx.mkSymbol("initHyperRule"))

    //FIXME: We don't need Asserts
    /*
    val asserts_init = sim.rewriteAsserts(
      initLambda, initAsserts, 0,
      prevVarTable(0).flatten.map(p => p.asInstanceOf[smt.Symbol]),
      simRecord.clone(), havocTable(0))

    val initAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_init.map {
      assert =>
        log.debug("The assert " + assert.expr.toString)
        val errorDecl = ctx.mkFuncDecl("error_init_assert_" + getUniqueId().toString, Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)
        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }

        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        val and = ctx.mkAnd(initConjunctsZ3(0), not, applyDecl(invHyperDecl, initHyperSymbolsBound))
        val rule = createForall(initHyperSymbolsBound.toArray, ctx.mkImplies(and, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
        fp.addRule(rule, ctx.mkSymbol("init_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap
    */

    // FIXME: We don't need HyperAsserts
    /*
    val asserts_init_hyper = sim.rewriteHyperAsserts(
      initLambda, 0, initHyperAsserts, simRecord, 0, scope, prevVarTable.toList)
    */

    /*
    val initHyperAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_init_hyper.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_init_hyp_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        val pcExpr = assert.pathCond

        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        //val not = ctx.mkNot(exprToZ3(assert.expr).asInstanceOf[z3.BoolExpr])
        val and = ctx.mkAnd(applyDecl(invHyperDecl, initHyperSymbolsBound), ctx.mkAnd(initConjunctsZ3 ++ hypAssumesInitZ3 : _*))
        val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall(initHyperSymbolsBound.toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("init_hyp_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap */


    var symTabStep = sim.symbolTable
    for (j <- 1 to numCopies) {
      symTabStep = sim.newInputSymbols(sim.getInitSymbolTable(scope), inputStep, scope)
      inputStep += 1
      simRecord(j - 1) += symTabStep
      val new_vars = sim.getVarsInOrder(symTabStep.map(_.swap), scope)
      val next_havocs = sim.getHavocs(nextLambda.e)
      val havoc_subs = next_havocs.map {
        havoc =>
          val s = havoc.id.split("_")
          val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
          (havoc, sim.newHavocSymbol(name, havoc.typ))
      }
      val next_conjunct = sim.substitute(sim.betaSubstitution(nextLambda, (prevVarTable(j - 1) ++ new_vars).flatten), havoc_subs)
      //addAssumptionToTree(next_conjunct, List.empty)
      nextConjuncts += next_conjunct

      havocTable(j - 1) = havoc_subs
      prevVarTable(j - 1) = new_vars
    }

    val nextSymbolsSmt = sim.getVarsInOrder(simRecord(0)(1).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    //addZ3Symbols(nextSymbolsSmt)
    //addBoundVariables(nextSymbolsSmt, None, false)

    //val nextSymbolsZ3 = nextSymbolsSmt.map(sym => hornSymbolMap(sym))
    val nextSymbolsBound = nextSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val nextHyperSymbolsSmt = simRecord.flatMap {
      frameTable =>
        sim.getVarsInOrder(frameTable(1).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    }.toList

    // This needs to be computed using the previous bounds
    val nextConjunctInitZ3 = exprToZ3(nextConjuncts(0)).asInstanceOf[z3.BoolExpr]
    //val nextRule = createForall((initSymbolsBound ++ nextSymbolsBound).toArray,
    //  ctx.mkImplies(ctx.mkAnd(nextConjunctInitZ3, applyDecl(invSingle, initSymbolsBound)), applyDecl(invSingle, nextSymbolsBound))
    //)
    //fp.addRule(nextRule, ctx.mkSymbol("nextRule"))

    // FIXME: We don't need the next Asserts
    /*
    val asserts_next = sim.rewriteAsserts(
      nextLambda, nextAsserts, 1,
      sim.getVarsInOrder(simRecord(0)(1 - 1).map(_.swap), scope).flatten.map(p => p.asInstanceOf[smt.Symbol]) ++
        prevVarTable(0).flatten.map(p => p.asInstanceOf[smt.Symbol]), simRecord.clone(), havocTable(0))
    //UclidMain.println("Expr Map before Assertions " + exprToZ3.memoHashMap.toString)
    */

    val nextConjunctsZ3 = nextConjuncts.map(expr =>
      exprToZ3(expr).asInstanceOf[z3.BoolExpr])
    //val nextHyperSymbolsZ3 = nextHyperSymbolsSmt.map(sym => hornSymbolMap(sym))
    val nextHyperSymbolsBound = nextHyperSymbolsSmt.
      map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    /*
    val nextAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_next.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_next_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)
        log.debug("Error Next: " + assert.expr.toString)
        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        val and = ctx.mkAnd(nextConjunctInitZ3, applyDecl(invHyperDecl, initHyperSymbolsBound), applyDecl(invHyperDecl, nextHyperSymbolsBound))//ctx.mkAnd(nextConjunctsZ3(0), initConjunctsZ3(0))
      val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall((initHyperSymbolsBound ++ nextHyperSymbolsBound).toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("next_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap
    */

    //addZ3Symbols(nextHyperSymbolsSmt)
    // Needed to flush out old subexpressions
    //clearExprMap()
    //addBoundVariables(nextHyperSymbolsSmt, None, true)

    //UclidMain.println("Expr Map Updated " + exprToZ3Horn.memoHashMap.toString)

    log.debug("" + nextHyperSymbolsBound.toString)
    log.debug("Init bound HyperSymbols " + initHyperSymbolsBound.toString)
    log.debug("Next Conjuncts " + nextConjunctsZ3.toString)



    val hyperAssumesNextZ3 = sim.rewriteHyperAssumes(nextLambda, 1,
      nextHyperAssumes, simRecord, 1, scope, prevVarTable.toList).map {
      exp => exprToZ3(exp).asInstanceOf[z3.BoolExpr]
    }

    log.debug("The next HyperAssumes " + nextHyperAssumes.toString)
    log.debug("The next HyperAssumesZ3 " + hyperAssumesNextZ3.toString)


    val nextHyperRule = createForall((initHyperSymbolsBound ++ nextHyperSymbolsBound).toArray,
      ctx.mkImplies(ctx.mkAnd(applyDecl(invHyperDecl, initHyperSymbolsBound), ctx.mkAnd(nextConjunctsZ3 ++ hyperAssumesNextZ3 : _*)),
        applyDecl(invHyperDecl, nextHyperSymbolsBound)
      )
    )
    fp.addRule(nextHyperRule, ctx.mkSymbol("nextHyperRule"))
    // FIXME: We don't need hyperAsserts
    /*
    val asserts_next_hyper = sim.rewriteHyperAsserts(nextLambda, 1, nextHyperAsserts, simRecord, 1, scope, prevVarTable.toList)
    val nextHyperAssertsMap: Map[AssertInfo, z3.FuncDecl] = asserts_next_hyper.map {
      assert =>
        val errorDecl = ctx.mkFuncDecl("error_next_hyp_assert_" + getUniqueId().toString(), Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        val pcExpr = assert.pathCond
        // If assertExpr has a CoverDecorator then we should not negate the expression here.
        val assertExpr = if (assert.decorators.contains(CoverDecorator)) {
          assert.expr
        } else {
          smt.OperatorApplication(smt.NegationOp, List(assert.expr))
        }
        val checkExpr = if (pcExpr == smt.BooleanLit(true)) {
          assertExpr
        } else {
          smt.OperatorApplication(smt.ConjunctionOp, List(pcExpr, assertExpr))
        }

        val not = exprToZ3(checkExpr).asInstanceOf[z3.BoolExpr]
        //UclidMain.println("Not in hyperAssert " + not.toString)
        //val not = ctx.mkNot(exprToZ3(assert.expr).asInstanceOf[z3.BoolExpr])
        val and = applyDecl(invHyperDecl, nextHyperSymbolsBound)
        val implies = ctx.mkImplies(ctx.mkAnd(and, not), errorDecl.apply().asInstanceOf[z3.BoolExpr])
        val rule = createForall(nextHyperSymbolsBound.toArray, implies)
        fp.addRule(rule, ctx.mkSymbol("next_hyp_assert_" + getUniqueId().toString))
        assert -> errorDecl
    }.toMap
    */

    log.debug(fp.toString())

    /*
    UclidMain.println("Querying Init Asserts")
    initAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }

    /*UclidMain.println("Querying Init Hyper Asserts")
    initHyperAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }*/

    UclidMain.println("Querying Next Asserts")
    nextAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        UclidMain.println(fp.query(Array(p._2)).toString)

    }

    UclidMain.println("Querying Next Hyper Asserts")
    nextHyperAssertsMap.foreach {
      p =>
        UclidMain.println("Querying " + p._1.toString)
        val rfp = fp.query(Array(p._2))
        log.debug(rfp.toString())
        if (rfp == z3.Status.SATISFIABLE) {
          //val cex = fp.getAnswer()
          UclidMain.println("SATISFIABLE")
          //UclidMain.println("CEX : " + cex.toString())
        }
        else {
          UclidMain.println(rfp.toString())
        }
      //log.debug("Args: " + fp.getAnswer().getArgs())
      //UclidMain.println(fp.query(Array(p._2)).toString)

    }
    */
    // 1) Make copies of all the variables
    // 2) Make error decls for each of the asserts and hyperAsserts
    // 3) Encode everything like the example
    // 4) For each error relation query the fixed point
    fp

  }


  def solveTaintLambdasV2(combinedInitLambda: smt.Lambda, combinedNextLambda: smt.Lambda, scope: Scope) = {
    // Solves T2 taints
    // The taint lambda and the transition system is unioned to resolve all dependencies
    //log.debug("The combinedInitLambda " +  combinedInitLambda)
    //log.debug("The combinedNextLambda  " + combinedNextLambda)
    z3.Global.setParameter("fp.engine", "spacer")
    val sorts = combinedInitLambda.ids.map(id => getZ3Sort(id.typ)) ++ List(ctx.mkIntSort())
    val stepVar0 = sim.newStateSymbol("stepVar0", 0, smt.IntType)
    val stepVar1 = sim.newStateSymbol("stepVar1", 1, smt.IntType)

    def applyDecl(f : z3.FuncDecl, args: List[z3.Expr]) : z3.BoolExpr = {
      Predef.assert(f.getDomainSize() == args.length)
      /*f.getDomain.zip(args).foreach {
        d =>
          UclidMain.println("domain typ and arg typ" + (d._1, d._2.getSort))
      }
      UclidMain.println("The args to applyDecl " + args.toString)*/
      f.apply(args : _*).asInstanceOf[z3.BoolExpr]
    }

    var qId = 0
    var skId = 0


    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    var stepOffset = 0
    val simRecord = new sim.SimulationTable
    val frameTable = new sim.FrameTable
    val taintFrameTable = new TaintFrameTable
    var prevVarTable = new ArrayBuffer[List[List[smt.Expr]]]()
    var havocTable = new ArrayBuffer[List[(smt.Symbol, smt.Symbol)]]()
    var prevTaintVarTable = new ArrayBuffer[List[smt.Expr]]()
    var taintAssumes = new ArrayBuffer[smt.Expr]()

    val fp = ctx.mkFixedpoint()
    val invTaintDecl = ctx.mkFuncDecl("invTaintV2", sorts.toArray , boolSort)
    fp.registerRelation(invTaintDecl)


    val taintInitSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, false), stepOffset, false, scope)
    stepOffset += 1
    taintFrameTable += taintInitSymTab
    val initTaintVars = getTaintVarsInOrder(taintInitSymTab.map(_.swap), scope)
    prevTaintVarTable += initTaintVars.flatten

    val initSymTab = sim.newInputSymbols(sim.getInitSymbolTable(scope), stepOffset, scope)
    stepOffset += 1
    frameTable += initSymTab
    var prevVars = sim.getVarsInOrder(initSymTab.map(_.swap), scope)
    prevVarTable += prevVars
    simRecord += frameTable

    val init_havocs = sim.getHavocs(combinedInitLambda.e)
    val havoc_subs = init_havocs.map {
      havoc =>
        val s = havoc.id.split("_")
        val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
        (havoc, sim.newHavocSymbol(name, havoc.typ))
    }
    havocTable += havoc_subs
    val init_conjunct = sim.substitute(sim.betaSubstitution(combinedInitLambda, prevVars.flatten ++ initTaintVars.flatten), havoc_subs)
    //addAssumptionToTree(init_conjunct, List.empty)
    val stepVar0Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar0, smt.IntLit(0)))
    val initConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(init_conjunct, stepVar0Eq))

    log.debug("++++++++ initConjunctFinal ++++++++\n" + initConjunctFinal.toString)

    val initTaintSymbolsSmt = getTaintVarsInOrder(taintFrameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol]) ++ List(stepVar0)
    val initTaintSymbolsBound = initTaintSymbolsSmt.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])

    val initSymbolsSmt = sim.getVarsInOrder(frameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol])
    val initSymbolsBound = initSymbolsSmt.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    val initConjunctZ3 = exprToZ3(initConjunctFinal).asInstanceOf[z3.BoolExpr]

    val initRule = createForall((initSymbolsBound ++ initTaintSymbolsBound).toArray, ctx.mkImplies(initConjunctZ3, applyDecl(invTaintDecl, initSymbolsBound ++ initTaintSymbolsBound)))
    fp.addRule(initRule, ctx.mkSymbol("initRule"))
    //log.debug("+++++++ The V2 Fixed Point +++++++\n" + fp.toString)


    val symTabStep = sim.newInputSymbols(sim.getInitSymbolTable(scope), stepOffset, scope)
    stepOffset += 1
    simRecord(1 - 1) += symTabStep
    val new_vars = sim.getVarsInOrder(symTabStep.map(_.swap), scope)
    val next_havocs = sim.getHavocs(combinedNextLambda.e)
    val havoc_subs_next = next_havocs.map {
      havoc =>
        val s = havoc.id.split("_")
        val name = s.takeRight(s.length - 2).foldLeft("")((acc, p) => acc + "_" + p)
        (havoc, sim.newHavocSymbol(name, havoc.typ))
    }
    havocTable += havoc_subs_next
    prevVarTable += new_vars
    frameTable += symTabStep

    val taintNextSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, false), stepOffset, false, scope)
    stepOffset += 1
    taintFrameTable += taintNextSymTab
    val nextTaintVars = getTaintVarsInOrder(taintNextSymTab.map(_.swap), scope)
    prevTaintVarTable += nextTaintVars.flatten

    val stepVar1Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar1,
      smt.OperatorApplication(smt.IntAddOp, List(stepVar0, smt.IntLit(1)))))

    //log.debug("Combined Next Lambda " + combinedNextLambda.toString)
    val next_conjunct = sim.substitute(sim.betaSubstitution(combinedNextLambda, (prevVarTable(0).flatten ++ prevTaintVarTable(0)) ++ (prevVarTable(1).flatten ++ prevTaintVarTable(1))), havoc_subs_next)
    //log.debug("THE NEXT CONJUNCT\n" + next_conjunct.toString)
    val nextSymbolsSmt = sim.getVarsInOrder(frameTable(1).map(_.swap), scope).flatten
    val nextSymbolsBound = nextSymbolsSmt.map(smtVar => smtVar.asInstanceOf[smt.Symbol]).map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    val nextTaintSymbolsBound = getTaintVarsInOrder(taintFrameTable(1).map(_.swap), scope).flatten.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr]) ++ List(exprToZ3(stepVar1).asInstanceOf[z3.Expr])
    val nextConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(next_conjunct, stepVar1Eq))

    val nextConjunctZ3 = exprToZ3(nextConjunctFinal).asInstanceOf[z3.BoolExpr]
    val nextRule = createForall((initSymbolsBound ++ nextSymbolsBound ++ initTaintSymbolsBound ++ nextTaintSymbolsBound).toArray,
      ctx.mkImplies(ctx.mkAnd(nextConjunctZ3, applyDecl(invTaintDecl, initSymbolsBound ++ initTaintSymbolsBound)), applyDecl(invTaintDecl, nextSymbolsBound ++ nextTaintSymbolsBound))
    )
    fp.addRule(nextRule, ctx.mkSymbol("nextRule"))
    //log.debug("+++++++ The V2 Fixed Point +++++++\n" + fp.toString)
    //addAssumptionToTree(next_conjunct, List.empty)
    //nextConjuncts += next_conjunct

    val taintErrorMap = initTaintSymbolsBound.map {
      sym =>
        val errorDecl = ctx.mkFuncDecl("error_taint_" + getUniqueId().toString() + "_" + sym.toString, Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        if (sym.isInstanceOf[z3.BoolExpr]) {
          val stepGt = exprToZ3(smt.OperatorApplication(smt.IntGTOp, List(stepVar0, smt.IntLit(0)))).asInstanceOf[z3.BoolExpr]
          val and = ctx.mkAnd(ctx.mkNot(sym.asInstanceOf[z3.BoolExpr]), applyDecl(invTaintDecl, initSymbolsBound ++ initTaintSymbolsBound), stepGt)
          val rule = createForall((initTaintSymbolsBound ++ initSymbolsBound).toArray, ctx.mkImplies(and, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
          fp.addRule(rule, ctx.mkSymbol("error_rule_taint_" + getUniqueId().toString() + "_" + sym.toString))
          sym -> Some(errorDecl)
        }
        else {
          sym -> None
        }

    }.toMap

    log.debug("**** The Taint FixedPoint **** \n" + fp.toString)
    log.debug("Querying Taint Vars")
    taintErrorMap.foreach {
      p =>
        log.debug("Querying " + p._1.toString)
        p._2 match {
          case Some(err) => {
            val rfp = fp.query(Array[z3.FuncDecl](err))
            log.debug(rfp.toString())
            if (rfp == z3.Status.SATISFIABLE) {
              val cex = fp.getAnswer()
              val l = cex.getNumArgs()
              log.debug("CEX : " + cex.toString())
              log.debug("CEX len : " + l.toString)
              //log.debug("Args: " + fp.getAnswer().getArgs())

            }
          }
          case None => log.debug("Array/Non Boolean variable not queried.")
        }
    }



  }


  def solveTaintLambdas(initTaintLambda: smt.Lambda, nextTaintLambda: smt.Lambda, isSimple: Boolean, scope: Scope) = {

    z3.Global.setParameter("fp.engine", "spacer")
    val sorts = initTaintLambda.ids.map(id => getZ3Sort(id.typ)) ++ List(ctx.mkIntSort())
    val stepVar0 = sim.newStateSymbol("stepVar0", 0, smt.IntType)
    val stepVar1 = sim.newStateSymbol("stepVar1", 1, smt.IntType)



    def applyDecl(f : z3.FuncDecl, args: List[z3.Expr]) : z3.BoolExpr = {
      Predef.assert(f.getDomainSize() == args.length)
      /*f.getDomain.zip(args).foreach {
        d =>
          UclidMain.println("domain typ and arg typ" + (d._1, d._2.getSort))
      }
      UclidMain.println("The args to applyDecl " + args.toString)*/
      f.apply(args : _*).asInstanceOf[z3.BoolExpr]
    }

    var qId = 0
    var skId = 0


    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    val fp = ctx.mkFixedpoint()
    var stepOffset = 0
    val taintFrameTable = new TaintFrameTable
    var prevTaintVarTable = new ArrayBuffer[List[smt.Expr]]()
    val taintInitSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, isSimple), stepOffset, isSimple, scope)
    stepOffset += 1
    taintFrameTable += taintInitSymTab
    val initTaintVars = getTaintVarsInOrder(taintInitSymTab.map(_.swap), scope)
    prevTaintVarTable += initTaintVars.flatten
    log.debug("Init Taint Lambda " + initTaintLambda)
    log.debug("Init Taint Vars " + initTaintVars.flatten)
    val initTaintConjunct = sim.betaSubstitution(initTaintLambda, initTaintVars.flatten)
    val stepVar0Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar0, smt.IntLit(0)))
    val initTaintConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(initTaintConjunct, stepVar0Eq))

    val taintNextSymTab = newTaintInputSymbols(getTaintSymbolTable(scope, isSimple), stepOffset, isSimple, scope)
    stepOffset += 1
    taintFrameTable += taintNextSymTab
    val nextTaintVars = getTaintVarsInOrder(taintNextSymTab.map(_.swap), scope)


    prevTaintVarTable += nextTaintVars.flatten
    log.debug("Next Taint Lambda" + nextTaintLambda)
    val nextTaintConjunct = sim.betaSubstitution(nextTaintLambda, prevTaintVarTable(1 - 1) ++ nextTaintVars.flatten)
    val stepVar1Eq = smt.OperatorApplication(smt.EqualityOp, List(stepVar1,
                        smt.OperatorApplication(smt.IntAddOp, List(stepVar0, smt.IntLit(1)))))
    val nextTaintConjunctFinal = smt.OperatorApplication(smt.ConjunctionOp, List(nextTaintConjunct, stepVar1Eq))
    val invTaintDecl = ctx.mkFuncDecl("invTaint", sorts.toArray , boolSort)
    fp.registerRelation(invTaintDecl)

    val initTaintSymbolsSmt = getTaintVarsInOrder(taintFrameTable(0).map(_.swap), scope).flatten.map(smtVar => smtVar.asInstanceOf[smt.Symbol]) ++ List(stepVar0)
    val initTaintSymbolsBound = initTaintSymbolsSmt.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr])
    val nextTaintSymbolsBound = getTaintVarsInOrder(taintFrameTable(1).map(_.swap), scope).flatten.map(smtVar => exprToZ3(smtVar).asInstanceOf[z3.Expr]) ++ List(exprToZ3(stepVar1).asInstanceOf[z3.Expr])

    val initTaintConjunctZ3 = exprToZ3(initTaintConjunctFinal).asInstanceOf[z3.BoolExpr]
    val nextTaintConjunctZ3 = exprToZ3(nextTaintConjunctFinal).asInstanceOf[z3.BoolExpr]

    log.debug("Next Taint Conjunct Z3 \n" + nextTaintConjunctZ3.toString)
    val initTaintRule = createForall(initTaintSymbolsBound.toArray, ctx.mkImplies(initTaintConjunctZ3, applyDecl(invTaintDecl, initTaintSymbolsBound)))
    fp.addRule(initTaintRule, ctx.mkSymbol("initTaintRule"))

    val nextTaintRule = createForall((initTaintSymbolsBound ++ nextTaintSymbolsBound).toArray,
      ctx.mkImplies(ctx.mkAnd(nextTaintConjunctZ3, applyDecl(invTaintDecl, initTaintSymbolsBound)), applyDecl(invTaintDecl, nextTaintSymbolsBound))
    )
    fp.addRule(nextTaintRule, ctx.mkSymbol("nextTaintRule"))


    val taintErrorMap = initTaintSymbolsBound.map {
      sym =>
        val errorDecl = ctx.mkFuncDecl("error_taint_" + getUniqueId().toString() + "_" + sym.toString, Array[z3.Sort](), boolSort)
        fp.registerRelation(errorDecl)

        if (sym.isInstanceOf[z3.BoolExpr]) {
          val stepGt = exprToZ3(smt.OperatorApplication(smt.IntGTOp, List(stepVar0, smt.IntLit(0)))).asInstanceOf[z3.BoolExpr]
          val and = ctx.mkAnd(ctx.mkNot(sym.asInstanceOf[z3.BoolExpr]), applyDecl(invTaintDecl, initTaintSymbolsBound), stepGt)
          val rule = createForall(initTaintSymbolsBound.toArray, ctx.mkImplies(and, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
          fp.addRule(rule, ctx.mkSymbol("error_rule_taint_" + getUniqueId().toString() + "_" + sym.toString))
          sym -> Some(errorDecl)
        }
        else {
          sym -> None
        }

    }.toMap

    log.debug("**** The Taint FixedPoint **** \n" + fp.toString)
    log.debug("Querying Taint Vars")
    taintErrorMap.foreach {
      p =>
        log.debug("Querying " + p._1.toString)
        p._2 match {
          case Some(err) => {
            val rfp = fp.query(Array[z3.FuncDecl](err))
            log.debug(rfp.toString())
            if (rfp == z3.Status.SATISFIABLE) {
              val cex = fp.getAnswer()
              log.debug("CEX : " + cex.toString())
              //log.debug("Args: " + fp.getAnswer().getArgs())

            }
          }
          case None => log.debug("Array/Non Boolean variable not queried.")
        }
    }
  }

  def parseCEX(cex: z3.Expr) = {
    Predef.assert(cex.isInstanceOf[z3.BoolExpr])
    Predef.assert(cex.isAnd() || cex.getNumArgs() == 1)
    val args = cex.getArgs()



  }

  def taintRefinement(combinedInitLambda: smt.Lambda, combinedNextLambda: smt.Lambda, hyperAssumes: List[smt.Expr], hyperAsserts: List[smt.Expr]) = {
    // HyperAsserts are assumed to be of the form: hyperselect(v, 1) == hyperselect(v, 2)


  }
}


object Z3HornSolver
{
  def test() : Unit = {
    // Transition system
    //
    // Init(x, y) = x == 0 && y > 1
    // Transition(x, y, x', y') = (x' = x + 1) && (y' = y + x)
    // Bad(x, y) = x >= y
    //
    z3.Global.setParameter("fp.engine", "pdr")

    val ctx = new z3.Context()
    val intSort = ctx.mkIntSort()
    val boolSort = ctx.mkBoolSort()
    val fp = ctx.mkFixedpoint()

    val sorts2 = Array[z3.Sort](intSort, intSort)
    val invDecl = ctx.mkFuncDecl("inv", sorts2, boolSort)
    val errorDecl = ctx.mkFuncDecl("error", Array[z3.Sort](), boolSort)

    val symbolx = ctx.mkSymbol(0)
    val symboly = ctx.mkSymbol(1)
    val symbols2 = Array[z3.Symbol](symbolx, symboly)
    val x = ctx.mkBound(0, sorts2(0)).asInstanceOf[z3.ArithExpr]
    val y = ctx.mkBound(1, sorts2(1)).asInstanceOf[z3.ArithExpr]
    
    def applyDecl(f : z3.FuncDecl, x : z3.ArithExpr, y : z3.ArithExpr) : z3.BoolExpr = {
      f.apply(x, y).asInstanceOf[z3.BoolExpr]
    }
    var qId = 0
    var skId = 0
    def createForall(sorts : Array[z3.Sort], symbols : Array[z3.Symbol], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(sorts, symbols, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }
    
    fp.registerRelation(invDecl)
    fp.registerRelation(errorDecl)

    // x >= 0 && y >= 0 ==> inv(x, y)
    val xEq0 = ctx.mkEq(x, ctx.mkInt(0))
    val yGt1 = ctx.mkGt(y, ctx.mkInt(1))
    val initCond = ctx.mkAnd(xEq0, yGt1)
    val initRule = createForall(sorts2, symbols2, ctx.mkImplies(initCond, applyDecl(invDecl, x, y)))
    //val initRule = ctx.mkImplies(initCond, applyDecl(invDecl, x, y))

    // inv(x, y) ==> inv(x+1, y+x)
    val xPlus1 = ctx.mkAdd(x, ctx.mkInt(1))
    val yPlusx = ctx.mkAdd(y, x)
    val guard = applyDecl(invDecl, x, y)
    val trRule = createForall(sorts2, symbols2, ctx.mkImplies(guard, applyDecl(invDecl, xPlus1, yPlusx)))
    //val trRule = ctx.mkImplies(guard, applyDecl(invDecl, xPlus1, yPlusx))

    val yProp1 = ctx.mkGe(x, y)
    val propGuard = ctx.mkAnd(applyDecl(invDecl, x, y), yProp1)
    val propRule = createForall(sorts2, symbols2, ctx.mkImplies(propGuard, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
    //val propRule = ctx.mkImplies(propGuard, errorDecl.apply().asInstanceOf[z3.BoolExpr])
    
    fp.addRule(initRule, ctx.mkSymbol("initRule"))
    fp.addRule(trRule, ctx.mkSymbol("trRule"))
    fp.addRule(propRule, ctx.mkSymbol("propRule"))

    UclidMain.println(fp.toString())

    // property.
    UclidMain.println (fp.query(Array(errorDecl)).toString)
  }

  def test1() : Unit = {
    // Transition system
    //
    // Init(x, y) = x == 0 && y > 1
    // Transition(x, y, x', y') = (x' = x + 1) && (y' = y + x)
    // Bad(x, y) = x >= y
    //
    z3.Global.setParameter("fp.engine", "spacer")

    val ctx = new z3.Context()
    val intSort = ctx.mkIntSort()
    val boolSort = ctx.mkBoolSort()
    val fp = ctx.mkFixedpoint()

    val sorts2 = Array[z3.Sort](intSort, boolSort)
    val invDecl = ctx.mkFuncDecl("inv", sorts2, boolSort)
    val errorDecl = ctx.mkFuncDecl("error", Array[z3.Sort](), boolSort)

    val x = ctx.mkConst("x", sorts2(0)).asInstanceOf[z3.ArithExpr]
    val y = ctx.mkConst("y", sorts2(1)).asInstanceOf[z3.BoolExpr]
    val bindings = Array[z3.Expr](x, y)

    def applyDecl(f : z3.FuncDecl, x : z3.ArithExpr, y : z3.BoolExpr) : z3.BoolExpr = {
      f.apply(x, y).asInstanceOf[z3.BoolExpr]
    }
    var qId = 0
    var skId = 0
    def createForall(bindings: Array[z3.Expr], e : z3.Expr) = {
      qId += 1
      skId += 1
      ctx.mkForall(bindings, e,
        0, Array[z3.Pattern](), Array[z3.Expr](), ctx.mkSymbol(qId), ctx.mkSymbol(skId))
    }

    fp.registerRelation(invDecl)
    fp.registerRelation(errorDecl)

    // x >= 0 && y >= 0 ==> inv(x, y)
    val xEq0 = ctx.mkEq(x, ctx.mkInt(0))
    val yGt1 = ctx.mkEq(y, ctx.mkBool(true))
    val initCond = ctx.mkAnd(xEq0, yGt1)
    val initRule = createForall(bindings, ctx.mkImplies(initCond, applyDecl(invDecl, x, y)))
    //val initRule = ctx.mkImplies(initCond, applyDecl(invDecl, x, y))

    // inv(x, y) ==> inv(x+1, y+x)
    val xPlus1 = ctx.mkAdd(x, ctx.mkInt(1))
    val yPlusx = ctx.mkEq(y, ctx.mkBool(false))
    val guard = applyDecl(invDecl, x, y)
    val trRule = createForall(bindings, ctx.mkImplies(guard, applyDecl(invDecl, xPlus1, yPlusx)))
    //val trRule = ctx.mkImplies(guard, applyDecl(invDecl, xPlus1, yPlusx))

    val yProp1 = ctx.mkGe(x, ctx.mkInt(2))
    val propGuard = ctx.mkAnd(applyDecl(invDecl, x, y), yProp1)
    val propRule = createForall(bindings, ctx.mkImplies(propGuard, errorDecl.apply().asInstanceOf[z3.BoolExpr]))
    //val propRule = ctx.mkImplies(propGuard, errorDecl.apply().asInstanceOf[z3.BoolExpr])

    fp.addRule(initRule, ctx.mkSymbol("initRule"))
    fp.addRule(trRule, ctx.mkSymbol("trRule"))
    fp.addRule(propRule, ctx.mkSymbol("propRule"))

    UclidMain.println(fp.toString())

    // property.
    UclidMain.println (fp.query(Array(errorDecl)).toString)
  }

}
