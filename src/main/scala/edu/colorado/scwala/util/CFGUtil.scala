package edu.colorado.scwala.util

import scala.collection.JavaConversions._
import scala.collection.JavaConversions.collectionAsScalaIterable
import scala.collection.JavaConversions.iterableAsScalaIterable
import com.ibm.wala.ipa.callgraph.CGNode
import com.ibm.wala.ssa.IR
import com.ibm.wala.ssa.ISSABasicBlock
import com.ibm.wala.ssa.SSACFG
import com.ibm.wala.ssa.SSAConditionalBranchInstruction
import com.ibm.wala.ssa.SSAGotoInstruction
import com.ibm.wala.ssa.SSAInstruction
import com.ibm.wala.ssa.SSAReturnInstruction
import com.ibm.wala.ssa.SSASwitchInstruction
import com.ibm.wala.ssa.SSAThrowInstruction
import com.ibm.wala.util.graph.Graph
import com.ibm.wala.util.graph.dominators.Dominators
import com.ibm.wala.util.graph.impl.GraphInverter
import com.ibm.wala.util.graph.traverse.BFSPathFinder
import com.ibm.wala.util.graph.traverse.DFS
import edu.colorado.thresher.core.WALACFGUtil
import com.ibm.wala.util.graph.NumberedGraph
import edu.colorado.scwala.translate.WalaBlock


object CFGUtil {
  
  val DEBUG = false
  
  private def getWhile(succOrPred : (SSACFG, WalaBlock) => Set[WalaBlock], startBlk : WalaBlock, 
                          cfg : SSACFG, test : WalaBlock => Boolean, inclusive : Boolean) : Set[WalaBlock] = {
    @annotation.tailrec
    def getWhileRec(blks : Set[WalaBlock], seen : Set[WalaBlock]) : Set[WalaBlock]= {
      val (passing, failing) = blks.partition(blk => test(blk))    
      val newSeen = if (inclusive) failing ++ seen.union(passing) else seen.union(passing)
      if (passing.isEmpty) newSeen
      else {
        val toExplore = passing.foldLeft (Set.empty[WalaBlock]) ((set,blk) => set ++ succOrPred(cfg, blk))
        // explore all succs that we have not already seen
        getWhileRec(toExplore diff newSeen, newSeen)
      }
    }
    getWhileRec(Set(startBlk), Set.empty)
  }
  
  //def getBlocksSatisfying()

  /**
   * @param inclusive if true, include the last blks that fail test 
   * @return transitive closure of successors of startBlk in cfg that pass test
   */
  def getSuccsWhile(startBlk : WalaBlock, cfg : SSACFG, test : WalaBlock => Boolean = _ => true, 
      inclusive : Boolean = false) : Set[WalaBlock] = 
    getWhile((cfg, blk) => getSuccessors(blk, cfg).toSet, startBlk, cfg, test, inclusive)

    /**
     * @param inclusive if true, return the last blks that fail test 
     * @return transitive closure of predecessors of startBlk in cfg that pass test
     */
  def getPredsWhile(startBlk : WalaBlock, cfg : SSACFG, test : WalaBlock => Boolean, inclusive : Boolean = false, exceptional : Boolean = false) : Set[WalaBlock] =    
    getWhile((cfg, blk) => 
      (if (exceptional) cfg.getPredNodes(blk.blk).toList else cfg.getNormalPredecessors(blk.blk).toList)
      .map(blk => new WalaBlock(blk)).toSet, startBlk, cfg, test, inclusive)

    
    // TODO: use inclusive?
    def getFallThroughBlocks(startBlk : WalaBlock, cfg : SSACFG, inclusive : Boolean = false, test : WalaBlock => Boolean =_ => true) : Set[WalaBlock] = {
      var last : WalaBlock = null
      // want to do getSuccsWhile(succs.size == 1, but we also want the last block that fails the test to be included
      val fallThrough = 
        getSuccsWhile(startBlk, cfg, (blk => { if (!test(blk)) { last = blk; false } else { 
          //val size = cfg.getNormalSuccessors(blk).size()
          val size = getSuccessors(blk, cfg).size
          if (size <= 1) true
          else { last = blk; false}
        }}), inclusive)
        //if (last != null) fallThrough + last // add last if applicable
        if (last != null && last != startBlk) fallThrough + last // add last if applicable
        else fallThrough
    } 
    
    /**
     * @return true if @param startBlk falls through to @param targetBlk (that is, if startBlk inevitably transitions
     * to targetBlk in a non-exceptional execution)
     */
    def fallsThroughTo(startBlk : WalaBlock, targetBlk : WalaBlock, cfg : SSACFG) : Boolean = 
      getFallThroughBlocks(startBlk, cfg).contains(targetBlk)
      
    def fallsThroughToConditional(startBlk : WalaBlock, cfg : SSACFG) : Boolean = 
      getFallThroughBlocks(startBlk, cfg).find(blk => CFGUtil.endsWithConditionalInstr(blk)).isDefined    
     
    def fallsThroughToWithoutLoopConstructs(startBlk : WalaBlock, targetBlk : WalaBlock, 
        breaksAndContinues : Map[WalaBlock,WalaBlock], cfg : SSACFG) : Boolean = 
      getFallThroughBlocks(startBlk, cfg, false, blk => !breaksAndContinues.contains(blk)).contains(targetBlk)
           
    def isReachableFrom(targetBlk : WalaBlock, startBlk : WalaBlock, cfg : SSACFG) : Boolean = {
      getSuccsWhile(startBlk, cfg).contains(targetBlk)
    }     
    
    def isReachableFromWithoutLoopConstructs(targetBlk : WalaBlock, startBlk : WalaBlock, 
                                             bodyBlocks : Set[WalaBlock], breaksAndContinues : Map[WalaBlock,WalaBlock], cfg : SSACFG, inclusive : Boolean = false) : Boolean = 
      getReachableWithoutLoopConstructs(startBlk, breaksAndContinues, bodyBlocks, cfg, inclusive).contains(targetBlk)
    /**
     * @return all blocks reachable without following breaks, continues, or edges
     */
      /*
    def getReachableWithoutLoopConstructs(startBlk : WalaBlock, breaksAndContinues : Map[WalaBlock,WalaBlock], 
        ir : IR, inclusive : Boolean = false) : Set[WalaBlock] = {
      // TODO: if conditional is a break or continue, need to follow the other branch
      // no need to exclude back edges explicitly because they are included in continues
      val cfg = ir.getControlFlowGraph
      val headers = LoopUtil.getLoopHeaders(ir)
      val succs = getSuccsWhile(startBlk, cfg, blk => !breaksAndContinues.contains(blk) || headers.contains(blk.getNumber), inclusive)

      if (DEBUG) {
        //val preds = cfg.getNormalPredecessors(startBlk.blk).toSet
        //val inter = succs.intersect(preds)
        //if (!inter.isEmpty) { println("getting succs from BB" + startBlk.getNumber + "\nINTER: "); inter.foreach(blk => println("BB" + blk.getNumber())) }
      }
      succs
    } //ensuring (_.intersect(cfg.getNormalPredecessors(startBlk).toSet).size == 0) // if this set contains the predecessors of the block, we probably followed a back edge
    */
    
    def getReachableWithoutLoopConstructs(startBlk : WalaBlock, breaksAndContinues : Map[WalaBlock,WalaBlock], bodyBlocks : Set[WalaBlock], cfg : SSACFG, 
      inclusive : Boolean = false) : Set[WalaBlock] = {       
      def getSuccs(blk : WalaBlock, cfg : SSACFG) : List[WalaBlock] = {
        val succs = getSuccessors(blk, cfg)
        if (breaksAndContinues.contains(blk)) 
          if (succs.size == 1) succs //List.empty[WalaBlock] // normal break / continue--single succ
          else {
            // one branch of a conditional or loop head is a break / continue. follow the succ that is NOT a break / continue
            val jmpSucc = breaksAndContinues.getOrElse(blk, sys.error("this can't happen"))
            succs.filterNot(blk => blk == jmpSucc) 
          }
        else succs
      }
      //getWhile((cfg, blk) => getSuccs(blk, cfg).toSet, startBlk, cfg, blk => !breaksAndContinues.contains(blk) || endsWithConditionalInstr(blk), inclusive)
      getWhile((cfg, blk) => getSuccs(blk, cfg).toSet, startBlk, cfg, blk => (bodyBlocks.isEmpty || bodyBlocks.contains(blk))
          && !breaksAndContinues.contains(blk) || endsWithConditionalInstr(blk), inclusive)
    } //ensuring (ret => !ret.contains(startBlk))_
          
    /**
     * @return true if @param blk0 and @param blk1 both have a single successor, and that successor is the same block
     */  
    def transitionToSameBlock(blk0 : WalaBlock, blk1 : WalaBlock, cfg : SSACFG) : Boolean = {
      val (succs0, succs1) = (getSuccessors(blk0, cfg), getSuccessors(blk1, cfg))
      if (succs0.size == succs1.size && succs0.size == 1) succs0 == succs1
      else false
    }
      
   /**
     * @return true if @param src falls through to exit block
     */
    def isExitBlock(src : WalaBlock, cfg : SSACFG) : Boolean = fallsThroughTo(src, new WalaBlock(cfg.exit().asInstanceOf[ISSABasicBlock]), cfg)
    
    /**
     * @return true if @param src falls through to a throw block
     */
    def isThrowBlock(src : WalaBlock, cfg : SSACFG) : Boolean = {
      var last : WalaBlock = src
      // want to do getSuccsWhile(succs.size == 1, but we also want the last block that fails the test to be included
      getSuccsWhile(src, cfg, (blk => { 
          //val size = cfg.getNormalSuccessors(blk).size()          
          val size = getSuccessors(blk, cfg).size
          if (size == 1) true 
          else { last = blk; false}
      }))
      endsWithThrowInstr(last)
    }
    
    /**
     * @return true if a catch block falls through to @param snk
     */
    def catchBlockFallsThroughTo(snk : WalaBlock, cfg : SSACFG) : Boolean = {
      // we could use cfg.getCatchBlocks(), but it returns a bitvector that is a pain to
      // iterate over
      val catchBlocks = cfg.filter(blk => blk.isCatchBlock())
      catchBlocks.foldLeft (Set.empty[WalaBlock]) ((set, blk) => set ++ getFallThroughBlocks(new WalaBlock(blk), cfg))
      .contains(snk)
    }
    
    def catchBlockTransitionsTo(snk : WalaBlock, cfg : SSACFG) : Boolean = {
      // we could use cfg.getCatchBlocks(), but it returns a bitvector that is a pain to
      // iterate over
      val catchBlocks = cfg.filter(blk => blk.isCatchBlock())
      catchBlocks.foldLeft (Set.empty[WalaBlock]) ((set, blk) => set ++ getSuccsWhile(new WalaBlock(blk), cfg))
      .contains(snk)
    }
    /*
    // TODO: some Scala wizard who understands TypeTags could probably rewrite this in a nicer way
    def endsWithInstr[T <: SSAInstruction](blk : WalaBlock) (implicit tag : WeakTypeTag[T]) : Boolean = 
      if (blk.getLastInstructionIndex() > -1) {         
        val typ = reflect.runtime.currentMirror.reflect(blk.getLastInstruction()).symbol.toType        
        val res = typ <:< tag.tpe && tag.tpe <:< typ
        println("checking " + typ + " and " + tag.tpe + " res " + res)
        res
      } else false
      */
    // TODO: there's some way to do these with metaprogramming (see above commented method), but can't
    // quite figure it out under constrained time right now
    def endsWithThrowInstr(blk : WalaBlock) : Boolean = 
      blk.getLastInstructionIndex > -1 && blk.getLastInstruction.isInstanceOf[SSAThrowInstruction]
      
    def endsWithSwitchInstr(blk : WalaBlock) : Boolean = 
      blk.getLastInstructionIndex > -1 && blk.getLastInstruction.isInstanceOf[SSASwitchInstruction]
      
    def endsWithGotoInstr(blk : WalaBlock) : Boolean = 
      blk.getLastInstructionIndex > -1 && blk.getLastInstruction.isInstanceOf[SSAGotoInstruction]

    def endsWithConditionalInstr(blk : WalaBlock) : Boolean = 
      blk.getLastInstructionIndex > -1 && blk.getLastInstruction.isInstanceOf[SSAConditionalBranchInstruction]
            
    def endsWithReturnInstr(blk : WalaBlock) : Boolean = 
      blk.getLastInstructionIndex > -1 && blk.getLastInstruction.isInstanceOf[SSAReturnInstruction]         
      
    /**
     * Get the normal successors of a block AND any exceptional successors ending in a throw statement
     * this is necessary because WALA regards the transition to a throw block as an exceptional successor.
     * That is, if we have blk(v1 = new Exception) -> blk(throw v1), we will not see "blk(throw v1) as
     * a successor of "blk(v1 = new Exception)". This method is meant to correct this 
     */
    def getSuccessors(blk : WalaBlock, cfg : SSACFG) = {
      cfg.getExceptionalSuccessors(blk.blk).foldLeft (cfg.getNormalSuccessors(blk.blk).map(succ => new WalaBlock(succ)).toList) ((lst, succ) => {
        //if(endsWithInstr[SSAThrowInstruction](succ)) succs.add(succ)
        val walaBlk = new WalaBlock(succ)
        if (endsWithThrowInstr(walaBlk) && !walaBlk.isCatchBlock()) walaBlk :: lst
        else lst
      })
    }
      
  def getNormalPredecessors(blk : WalaBlock, cfg : SSACFG) : Iterable[WalaBlock] = 
    cfg.getNormalPredecessors(blk.blk).map(blk => new WalaBlock(blk))  
      
  def getThenBranch(blk : ISSABasicBlock, cfg : SSACFG) = getSuccessors(blk, cfg)(0)
    
  /*
  def BFSBwUntil[T](sources : Iterable[T], g : Graph[T]) : T = {
    // do a fair BFS backwards from each source in sources, stopping once the BFS starting from
    // each source encounters a common node
    
    // want to return set of joins ordered by relationship in CFG (lowest first)
    null
  }*/ 
  
  
  /** @ return a list of blocks whose immediate dominator is @param goal */
  def getJoins[T](goal : T, worklist : List[T], domInfo : Dominators[T]) : List[T] = { //cfg : SSACFG) : List[T] = {
    // we assume the nodes in preds are unique, that they are the predecessors of node,
    // and that they are ordered from in ascending order by number (or accordingly, depth in the CFG)
    // our objective is to push each node in preds up to goal, the immediate dominator of node 
    // while performing as many joins as possible
    @annotation.tailrec
    def getJoinsRec(worklist : List[T], acc : List[T]) : List[T] = worklist match {
      case node :: worklist =>
        val idom = domInfo.getIdom(node)
        println("node is " + node + " idom is " + idom)
        assert (idom != null, "couldn't get to " + goal)
        assert (idom != node)
        if (node == goal || idom == goal) getJoinsRec(worklist, node :: acc)
        else worklist match { // try to match with idom of next pred
          case nextPred :: worklist =>
            //assert(idom != nextPred, "nextPred eq idom " + nextPred)
            //if (idom == domInfo.getIdom(nextPred)) getJoinsRec(nextPred :: worklist, acc)
            if (idom == nextPred || idom == domInfo.getIdom(nextPred)) getJoinsRec(nextPred :: worklist, acc)
            else getJoinsRec(idom :: nextPred :: worklist, acc)
          case Nil => getJoinsRec(List(idom), acc) 
        }
      case Nil => acc
    }     
    getJoinsRec(worklist, List.empty[T])
  }
  
  def getJoin(node : ISSABasicBlock, preds : Iterable[ISSABasicBlock], cfg : SSACFG) : WalaBlock = {
   val domInfo = Dominators.make(cfg, cfg.entry())
   println("IDOM of BB" + node.getNumber() + " is BB" + domInfo.getIdom(node).getNumber())
   var last : ISSABasicBlock = null
  
   if (preds.size > 2) {
     println("getting joins from " + node + " for preds " + preds)
     val allJoins = getJoins(domInfo.getIdom(node), preds.toList.reverse, domInfo)
     println("joinz are " + allJoins)
   }
   
   preds.foreach(pred => println("pred BB" + pred.getNumber() + " dominated by BB" + domInfo.getIdom(pred).getNumber()))
   println("===")
   /*
   preds.toList.reverse.foreach(pred => {
     val idom = domInfo.getIdom(pred)
     assert (last == null || last == idom, "new idom " + idom + " doesn't match.")
     println("pred BB" + pred.getNumber() + " dominated by BB" + idom.getNumber())
     last = idom
   })*/
   val join = domInfo.getIdom(node)
   assert (endsWithConditionalInstr(join), "join " + join + " doesn't end with conditional " + cfg)
   join
    /*
    // TODO: rewrite this the right way so we don't have to worry about traversing back edges
    println("gettin join for " + preds)
    var first = true
    val predTC = preds.foldLeft (Set.empty[WalaBlock]) ((set, pred) => {
      val preds = getPredsWhile(pred, cfg, Util.RET_TRUE, true)
      println("preds are " + preds)
      if (first) { first = false; preds }
      else preds.intersect(set)
    })
    assert(!predTC.isEmpty, "no common pred for " + preds)
    val join = predTC.maxBy(blk => blk.getNumber) // TODO: is this safe?
    assert (endsWithConditionalInstr(join), "join " + join + " doesn't end with conditional " + cfg)
    join*/
    /*
    val iters = preds.map(pred => (Set.empty[WalaBlock], new BFSIterator(cfg, pred)))
    val newIters = iters.map(pair => {
      val (set, iter) = pair
      (if (iter.hasNext()) set + iter.next() else set, iter)
    })
    
    newIters.foldLeft (Set.empty[WalaBlock]) (pair => {
      val (set, _) = pair
      pair
    })*/
  } 
  
  def findInstr(node : CGNode, i : SSAInstruction) : Option[(ISSABasicBlock, Int)] = {
    val ir = node.getIR()
    val cfg = ir.getControlFlowGraph()
    // find index of instr in block
    val startBlk = ir.getBasicBlockForInstruction(i)
    if (startBlk == null) None
    else {
      val startLine = WALACFGUtil.findInstrIndexInBlock(i, startBlk.asInstanceOf[SSACFG#BasicBlock])
      Some(startBlk, startLine)
    }
  }
  
  /** @return true if @param snk is reachable from @param src in @param g */
  def isReachableFrom[T](snk : Int, src : Int, g : NumberedGraph[T]) : Boolean = {
    // subvert WALA caching issues. ugh
    val (newSrc, newSnk) = (g.getNode(src), g.getNode(snk))
    val finder = new BFSPathFinder(g, newSrc, newSnk)
    finder.find() != null 
  }
  
  def getBackwardReachableFrom[T](src : T, g : Graph[T], inclusive : Boolean) : List[T] = getReachableFrom(src, GraphInverter.invert(g)) 
    
  def getReachableFrom[T](src : T, g : Graph[T], inclusive : Boolean = true) : List[T] = {
    val srcs = new java.util.LinkedList[T]
    if (inclusive) srcs.add(src) else {
      val succs = g.getSuccNodes(src)
      while (succs.hasNext()) srcs.add(succs.next())
    }
    //DFS.getReachableNodes(g, java.util.Collections.singleton(src)).toList
    DFS.getReachableNodes(g, srcs).toList
  }
   
    
     
}