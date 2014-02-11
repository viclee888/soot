/* Soot - a J*va Optimization Framework
 * Copyright (C) 1997-1999 Raja Vallee-Rai
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.  
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */






package soot.jimple.toolkits.scalar;
import soot.options.*;

import soot.*;
import soot.jimple.*;
import soot.toolkits.scalar.*;
import soot.util.*;
import soot.toolkits.graph.*;
import java.util.*;

import java.util.ArrayDeque;
import java.util.Deque;

public class DeadAssignmentEliminator extends BodyTransformer
{
	public DeadAssignmentEliminator( Singletons.Global g ) {}
	public static DeadAssignmentEliminator v() { return G.v().soot_jimple_toolkits_scalar_DeadAssignmentEliminator(); }

	/**
	 * Eliminates dead code in a linear fashion.  Complexity is linear 
	 * with respect to the statements.
	 *
	 * Does not work on grimp code because of the check on the right hand
	 * side for side effects. 
	 */
	protected void internalTransform(Body b, String phaseName, Map<String, String> options)
	{
		boolean eliminateOnlyStackLocals = PhaseOptions.getBoolean(options, "only-stack-locals");

		if (Options.v().verbose()) {
			G.v().out.println("[" + b.getMethod().getName() + "] Eliminating dead code...");
		}
		
		if (Options.v().time()) {
			Timers.v().deadCodeTimer.start();
		}

		Chain<Unit> units = b.getUnits();
		Deque<Unit> q = new ArrayDeque<Unit>(units.size());

		// Make a first pass through the statements, noting 
		// the statements we must absolutely keep. 

		boolean isStatic = b.getMethod().isStatic();
		boolean allEssential = true;
		boolean checkInvoke = false;
		
		Local thisLocal = isStatic ? null : b.getThisLocal();

		for (Iterator<Unit> it = units.iterator(); it.hasNext(); ) {
			Unit s = it.next();
			boolean isEssential = true;
			
			if (s instanceof AssignStmt) {
				AssignStmt as = (AssignStmt) s;
				
				Value lhs = as.getLeftOp();
				Value rhs = as.getRightOp();
				
				// Stmt is of the form a = a which is useless
				if (lhs == rhs && lhs instanceof Local) {
					it.remove();
					continue;
				}
				
				if (lhs instanceof Local &&
					(!eliminateOnlyStackLocals || 
						((Local) lhs).getName().startsWith("$")
						|| lhs.getType() instanceof NullType))
				{				
				
					isEssential = false;
					
					if ( !checkInvoke ) {
						checkInvoke |= as.containsInvokeExpr();
					}

					if (rhs instanceof InvokeExpr || 
					    rhs instanceof ArrayRef || 
					    rhs instanceof CastExpr ||
					    rhs instanceof NewExpr ||
					    rhs instanceof NewArrayExpr ||
					    rhs instanceof NewMultiArrayExpr )
					{
					   // ArrayRef          : can have side effects (like throwing a null pointer exception)
					   // InvokeExpr        : can have side effects (like throwing a null pointer exception)
					   // CastExpr          : can trigger ClassCastException
					   // NewArrayExpr      : can throw exception
					   // NewMultiArrayExpr : can throw exception
					   // NewExpr           : can trigger class initialization					   
						isEssential = true;
					}
					
					if (rhs instanceof FieldRef) {
						// Can trigger class initialization
						isEssential = true;
					
						if (rhs instanceof InstanceFieldRef) {
							InstanceFieldRef ifr = (InstanceFieldRef) rhs;
						
							// Any InstanceFieldRef may have side effects,
							// unless the base is reading from 'this'
							// in a non-static method																
							isEssential = (isStatic || thisLocal != ifr.getBase());			
						} 
					}


					if (rhs instanceof DivExpr || rhs instanceof RemExpr) {
						BinopExpr expr = (BinopExpr) rhs;
						
						Type t1 = expr.getOp1().getType();
						Type t2 = expr.getOp2().getType();
						
						// Can trigger a division by zero   
						isEssential  = IntType.v().equals(t1) || LongType.v().equals(t1)
						            || IntType.v().equals(t2) || LongType.v().equals(t2);							
					}
				}
			}
			
			if (s instanceof NopStmt) {
				it.remove();
				continue;
			}
			
			if (isEssential) {
				q.addFirst(s);
			}
			
			allEssential &= isEssential;
		}
				
		if ( checkInvoke || !allEssential ) {		
			// Add all the statements which are used to compute values
			// for the essential statements, recursively 
			ExceptionalUnitGraph graph = new ExceptionalUnitGraph(b);
		
			LocalDefs defs = new SmartLocalDefs(graph, new SimpleLiveLocals(graph));
			LocalUses uses = new SimpleLocalUses(graph, defs);
	
			if ( !allEssential ) {		
				Set<Unit> essential = new HashSet<Unit>(graph.size());
				while (!q.isEmpty()) {
					Unit s = q.removeFirst();			
					if ( essential.add(s) ) {			
						for (ValueBox box : s.getUseBoxes()) {
							Value v = box.getValue();
							if (v instanceof Local) {
								Local l = (Local) v;
								q.addAll(defs.getDefsOfAt(l, s));
							}
						}
					}
				}
				// Remove the dead statements
				units.retainAll(essential);		
			}
		
			if ( checkInvoke ) {		
				// Eliminate dead assignments from invokes such as x = f(), where
				//	x is no longer used
		 
				List<AssignStmt> postProcess = new ArrayList<AssignStmt>();
				for ( Unit u : units ) {
					if (u instanceof AssignStmt) {
						AssignStmt s = (AssignStmt) u;				
						if (s.containsInvokeExpr()) {					
							// Just find one use of l which is essential 
							boolean deadAssignment = true;
							for (UnitValueBoxPair pair : uses.getUsesOf(s)) {
								if (units.contains(pair.unit)) {
									deadAssignment = false;
									break;
								}
							}				
							if (deadAssignment) {
								postProcess.add(s);
							}		
						}			
					}
				}
		
				for ( AssignStmt s : postProcess ) {
					// Transform it into a simple invoke.		 
					Stmt newInvoke = Jimple.v().newInvokeStmt(s.getInvokeExpr());
					newInvoke.addAllTagsOf(s);					
					units.swapWith(s, newInvoke);
				}
			}
		}
		if (Options.v().time()) {
			Timers.v().deadCodeTimer.end();
		}
	}
}
