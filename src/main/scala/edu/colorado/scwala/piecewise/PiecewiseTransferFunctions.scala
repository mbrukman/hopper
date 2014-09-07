package edu.colorado.scwala.piecewise

import scala.collection.JavaConversions._
import com.ibm.wala.analysis.pointers.HeapGraph
import com.ibm.wala.ipa.callgraph.CGNode
import com.ibm.wala.ipa.callgraph.CallGraph
import com.ibm.wala.ipa.callgraph.propagation.HeapModel
import com.ibm.wala.ipa.callgraph.propagation.PointerKey
import com.ibm.wala.ipa.cha.IClassHierarchy
import com.ibm.wala.ssa.SSAInvokeInstruction
import com.ibm.wala.util.graph.traverse.DFS
import edu.colorado.scwala.executor.TransferFunctions
import edu.colorado.scwala.state.LocalVar
import edu.colorado.scwala.state.Qry
import edu.colorado.scwala.state.Var
import edu.colorado.scwala.util.ClassUtil
import edu.colorado.scwala.util.GraphUtil
import edu.colorado.thresher.core.Options
import PiecewiseTransferFunctions._
import TransferFunctions._
import edu.colorado.scwala.util.Util
import com.ibm.wala.util.graph.traverse.BFSPathFinder
import com.ibm.wala.util.intset.OrdinalSet
import edu.colorado.scwala.state.Path


object PiecewiseTransferFunctions {
  // if true, drop constraints when a callee is relevant, but is more than Options.MAX_CALLSTACK_DEPTH steps away in the call graph.
  // this dropping will allow us to soundly report that the callee is irrelevant
  private val AGGRESSIVE_CALLEE_CONSTRAINT_DROPPING = true
}

/** extension of ordinary Thresher transfer functions using the relevance relation to do some things more precisely/efficiently */
class PiecewiseTransferFunctions(cg : CallGraph, hg : HeapGraph, hm : HeapModel, cha : IClassHierarchy, modRef : java.util.Map[CGNode, OrdinalSet[PointerKey]], 
  val rr : RelevanceRelation) extends TransferFunctions(cg, hg, hm, cha, modRef) {  
  
  private def doesCalleeModifyHeap(callee : CGNode, qry : Qry) : Boolean = {
    // set of nodes reachable from call at i
    val calleeReachable = DFS.getReachableNodes(cg, java.util.Collections.singleton(callee))
    
    // TODO: use mods first, then use prods to decide whether to drop or not. only compute prods for an individual constraint
    if (AGGRESSIVE_CALLEE_CONSTRAINT_DROPPING) {
      // +1 to account for the fact that callee has not been added to the call stack yet 
      val k = Path.MAX_CALLSTACK_DEPTH - (qry.callStack.size + 1)
      assert(k >= 0)
      
      // purposely getting producers rather than modifiers; we need to drop all constraints with producers in the callee in order to be sound, 
      // but it is sound (and more precise) not to drop constraints that can cause a refutation in the callee
      val constraintModMap = rr.getConstraintModifierMap(qry, ignoreLocalConstraints = true)
      //val constraintProdMap = rr.getConstraintProducerMap(qry, ignoreLocalConstraints = true)
      val kReachable = GraphUtil.kBFS(cg, callee, k)  
      
      // TODO: could do something slightly more consistent here like only dropping when no nodes are k-reachable, 
      // or dropping all constraints produceable in non-k-reachable nodes
      // the call is relevant if one or more nodes are k-reachable from callee
      // we will drop constraints from all nodes that not k-reachable
      constraintModMap.exists(entry => entry._2.exists(pair => {
        val node = pair._1
        calleeReachable.contains(pair) && { // node is reachable from callee
          val isKReachable = kReachable.contains(node)                    
          if (!isKReachable && rr.getProducers(entry._1, qry).exists(pair => pair._1 == node)) {
            // if node not k-reachable from callee AND node contains a producer statement for the current constraint, the node is relevant
            qry.removeConstraint(entry._1) // node not k-reachable. drop constraints
          } else if (DEBUG) println(s"Callee is Relevant: ${ClassUtil.pretty(callee)} because transitive callee is relevant: ${ClassUtil.pretty(node)}")
          // if isKReachable is true, the callee is relevant and we will exit via the double exists above
          isKReachable
        }})
      )
    } else {
      val constraintModMap = rr.getConstraintModifierMap(qry, ignoreLocalConstraints = true)
      constraintModMap.exists(entry => entry._2.exists(pair => {
        val node = pair._1
        val rel = calleeReachable.contains(node)
        if (rel && DEBUG) {
          println(s"Callee is relevant: ${ClassUtil.pretty(callee)} because transitive callee is relevant: ${ClassUtil.pretty(node)}")
          print("Instr "); ClassUtil.pp_instr(pair._2, node.getIR()); println(s" relevant to constraint ${entry._1}")
          val finder = new BFSPathFinder(cg, callee, node)
          val path = finder.find()
          println("Path is: "); path.foreach(n => println(ClassUtil.pretty(n)))
        }
        rel
      }))
    }
  
      /*rr.getModifierNodes(qry).exists(node => {
      val rel = calleeReachable.contains(node)
      if (rel) {
        println(s"Callee Relevant: ${ClassUtil.pretty(callee)}, node relevant ${ClassUtil.pretty(node)}")
        val finder = new BFSPathFinder(cg, callee, node)
        val path = finder.find()
        println("Path is: "); path.foreach(n => println(ClassUtil.pretty(n)))
      }
      rel
    })*/ 
  }
  
  override def isCallRelevant(i : SSAInvokeInstruction, caller : CGNode, callee : CGNode, qry : Qry) : Boolean = {    
    if (i.hasDef()) {
      val lhs = LocalVar(Var.makeLPK(i.getDef(), qry.node, hm))
      if (qry.localConstraints.exists(e => e.src == lhs)) {
        println(s"Retval relevant for call from ${ClassUtil.pretty(caller)}")
        true // retval relevant      
      } else doesCalleeModifyHeap(callee, qry)
    } else doesCalleeModifyHeap(callee, qry)
  }
  
}