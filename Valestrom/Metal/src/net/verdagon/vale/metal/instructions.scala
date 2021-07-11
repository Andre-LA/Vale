package net.verdagon.vale.metal

import net.verdagon.vale.templar.{FullNameT, IVarNameT, LetNormalTE}
import net.verdagon.vale.{vassert, vcurious, vfail, vwat}

// Common trait for all instructions.
sealed trait ExpressionH[+T <: KindH] {
  def resultType: ReferenceH[T]

  // Convenience functions for accessing this expression as the kind returning
  // a certain type.
  def expectStructAccess(): ExpressionH[StructRefH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ StructRefH(_)) => {
        this.asInstanceOf[ExpressionH[StructRefH]]
      }
      case _ => vfail()
    }
  }
  def expectInterfaceAccess(): ExpressionH[InterfaceRefH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ InterfaceRefH(_)) => {
        this.asInstanceOf[ExpressionH[InterfaceRefH]]
      }
    }
  }
  def expectRuntimeSizedArrayAccess(): ExpressionH[RuntimeSizedArrayTH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ RuntimeSizedArrayTH(_)) => {
        this.asInstanceOf[ExpressionH[RuntimeSizedArrayTH]]
      }
    }
  }
  def expectStaticSizedArrayAccess(): ExpressionH[StaticSizedArrayTH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ StaticSizedArrayTH(_)) => {
        this.asInstanceOf[ExpressionH[StaticSizedArrayTH]]
      }
    }
  }
  def expectIntAccess(): ExpressionH[IntH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ IntH(32)) => {
        this.asInstanceOf[ExpressionH[IntH]]
      }
    }
  }
  def expectI64Access(): ExpressionH[IntH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ IntH(64)) => {
        this.asInstanceOf[ExpressionH[IntH]]
      }
    }
  }
  def expectBoolAccess(): ExpressionH[BoolH] = {
    resultType match {
      case ReferenceH(_, _, _, x @ BoolH()) => {
        this.asInstanceOf[ExpressionH[BoolH]]
      }
    }
  }
}

// Produces an integer.
case class ConstantIntH(
  // The value of the integer.
  value: Long,
  bits: Int
) extends ExpressionH[IntH] {
  override def resultType: ReferenceH[IntH] = ReferenceH(ShareH, InlineH, ReadonlyH, IntH(bits))
}

// Produces a boolean.
case class ConstantBoolH(
  // The value of the boolean.
  value: Boolean
) extends ExpressionH[BoolH] {
  override def resultType: ReferenceH[BoolH] = ReferenceH(ShareH, InlineH, ReadonlyH, BoolH())
}

// Produces a string.
case class ConstantStrH(
  // The value of the string.
  value: String
) extends ExpressionH[StrH] {
  override def resultType: ReferenceH[StrH] = ReferenceH(ShareH, YonderH, ReadonlyH, StrH())
}

// Produces a float.
case class ConstantF64H(
  // The value of the float.
  value: Double
) extends ExpressionH[FloatH] {
  override def resultType: ReferenceH[FloatH] = ReferenceH(ShareH, InlineH, ReadonlyH, FloatH())
}

// Produces the value from an argument.
// There can only be one of these per argument; this conceptually destroys
// the containing argument and produces its value.
case class ArgumentH(
  resultType: ReferenceH[KindH],
  // The index of the argument, starting at 0.
  argumentIndex: Int
) extends ExpressionH[KindH]

// Takes a value from the source expression and puts it into a local
// variable on the stack.
case class StackifyH(
  // The expressions to read a value from.
  sourceExpr: ExpressionH[KindH],
  // Describes the local we're making.
  local: Local,
  // Name of the local variable. Used for debugging.
  name: Option[FullNameH]
) extends ExpressionH[KindH] {
  vassert(sourceExpr.resultType.kind == NeverH() ||
    sourceExpr.resultType == local.typeH)

  override def resultType: ReferenceH[StructRefH] = ReferenceH(ShareH, InlineH, ReadonlyH, ProgramH.emptyTupleStructRef)
}

// Takes a value from a local variable on the stack, and produces it.
// The local variable is now invalid, since its value has been taken out.
// See LocalLoadH for a similar instruction that *doesnt* invalidate the local var.
case class UnstackifyH(
  // Describes the local we're pulling from. This is equal to the corresponding
  // StackifyH's `local` member.
  local: Local
) extends ExpressionH[KindH] {
  // Panics if this is ever not the case.
  vcurious(local.typeH == resultType)

  override def resultType: ReferenceH[KindH] = local.typeH
}

// Takes a struct from the given expressions, and destroys it.
// All of its members are saved from the jaws of death, and put into the specified
// local variables.
// This creates those local variables, much as a StackifyH would, and puts into them
// the values from the dying struct instance.
case class DestroyH(
  // The expressions to take the struct from.
  structExpression: ExpressionH[StructRefH],
  // A list of types, one for each local variable we'll make.
  // TODO: If the vcurious below doesn't panic, get rid of this redundant member.
  localTypes: List[ReferenceH[KindH]],
  // The locals to put the struct's members into.
  localIndices: Vector[Local],
) extends ExpressionH[StructRefH] {
  vassert(localTypes.size == localIndices.size)
  vcurious(localTypes == localIndices.map(_.typeH).toList)

  override def resultType: ReferenceH[StructRefH] = ReferenceH(ShareH, InlineH, ReadonlyH, ProgramH.emptyTupleStructRef)
}

// Takes a struct from the given expressions, and destroys it.
// All of its members are saved from the jaws of death, and put into the specified
// local variables.
// This creates those local variables, much as a StackifyH would, and puts into them
// the values from the dying struct instance.
case class DestroyStaticSizedArrayIntoLocalsH(
  // The expressions to take the struct from.
  structExpression: ExpressionH[StaticSizedArrayTH],
  // A list of types, one for each local variable we'll make.
  // TODO: If the vcurious below doesn't panic, get rid of this redundant member.
  localTypes: List[ReferenceH[KindH]],
  // The locals to put the struct's members into.
  localIndices: Vector[Local]
) extends ExpressionH[StructRefH] {
  vassert(localTypes.size == localIndices.size)
  vcurious(localTypes == localIndices.map(_.typeH).toList)

  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}

// Takes a struct reference from the "source" expressions, and makes an interface reference
// to it, as the "target" reference, and puts it into another expressions.
case class StructToInterfaceUpcastH(
  // The expressions to get the struct reference from.
  sourceExpression: ExpressionH[StructRefH],
  // The type of interface to cast to.
  targetInterfaceRef: InterfaceRefH
) extends ExpressionH[InterfaceRefH] {
  // The resulting type will have the same ownership as the source expressions had.
  def resultType = ReferenceH(sourceExpression.resultType.ownership, sourceExpression.resultType.location, sourceExpression.resultType.permission, targetInterfaceRef)
}

// Takes an interface reference from the "source" expressions, and makes another reference
// to it, as the "target" inference, and puts it into another expressions.
case class InterfaceToInterfaceUpcastH(
  // The expressions to get the source interface reference from.
  sourceExpression: ExpressionH[InterfaceRefH],
  // The type of interface to cast to.
  targetInterfaceRef: InterfaceRefH
) extends ExpressionH[InterfaceRefH] {
  // The resulting type will have the same ownership as the source expressions had.
  def resultType = ReferenceH(sourceExpression.resultType.ownership, sourceExpression.resultType.location, sourceExpression.resultType.permission, targetInterfaceRef)
}

// Takes a reference from the given "source" expressions, and puts it into an *existing*
// local variable.
case class LocalStoreH(
  // The existing local to store into.
  local: Local,
  // The expressions to get the source reference from.
  sourceExpression: ExpressionH[KindH],
  // Name of the local variable, for debug purposes.
  localName: FullNameH
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = local.typeH
}

// Takes a reference from the given local variable, and puts it into a new expressions.
// This can never move a reference, only alias it. The instruction which can move a
// reference is UnstackifyH.
case class LocalLoadH(
  // The existing local to load from.
  local: Local,
  // The ownership of the resulting reference. This doesn't have to
  // match the ownership of the source reference. For example, we might want
  // to load a constraint reference from an owning local.
  targetOwnership: OwnershipH,
  // The permission of the resulting reference. This doesn't have to
  // match the ownership of the source reference. For example, we might want
  // to load a constraint reference from an owning local.
  targetPermission: PermissionH,
  // Name of the local variable, for debug purposes.
  localName: FullNameH
) extends ExpressionH[KindH] {
  vassert(targetOwnership != OwnH) // must unstackify to get an owning reference

  override def resultType: ReferenceH[KindH] = {
    val location =
      (targetOwnership, local.typeH.location) match {
        case (BorrowH, _) => YonderH
        case (WeakH, _) => YonderH
        case (OwnH, location) => location
        case (ShareH, location) => location
      }
    ReferenceH(targetOwnership, location, targetPermission, local.typeH.kind)
  }
}

// Turns a constraint ref into a weak ref.
case class NarrowPermissionH(
  // Expression containing the constraint reference to turn into a weak ref.
  refExpression: ExpressionH[KindH],
  // The permission of the resulting reference. This doesn't have to
  // match the ownership of the source reference. For example, we might want
  // to load a constraint reference from an owning local.
  targetPermission: PermissionH,
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = {
    val ReferenceH(ownership, location, permission, kind) = refExpression.resultType
    (permission, targetPermission) match {
      case (ReadwriteH, ReadonlyH) =>
      case _ => vwat()
    }
    ReferenceH(ownership, location, targetPermission, kind)
  }
}

// Takes a reference from the given "source" expressions, and swaps it into the given
// struct's member. The member's old reference is put into a new expressions.
case class MemberStoreH(
  resultType: ReferenceH[KindH],
  // Expression containing a reference to the struct whose member we will swap.
  structExpression: ExpressionH[StructRefH],
  // Which member to swap out, starting at 0.
  memberIndex: Int,
  // Expression containing the new value for the struct's member.
  sourceExpression: ExpressionH[KindH],
  // Name of the member, for debug purposes.
  memberName: FullNameH
) extends ExpressionH[KindH]

// Takes a reference from the given "struct" expressions, and copies it into a new
// expressions. This can never move a reference, only alias it.
case class MemberLoadH(
  // Expression containing a reference to the struct whose member we will read.
  structExpression: ExpressionH[StructRefH],
  // Which member to read from, starting at 0.
  memberIndex: Int,
//  // The ownership to load as. For example, we might load a constraint reference from a
//  // owning Car reference member.
//  targetOwnership: OwnershipH,
//  // The permission of the resulting reference. This doesn't have to
//  // match the ownership of the source reference. For example, we might want
//  // to load a constraint reference from an owning local.
//  targetPermission: PermissionH,
  // The type we expect the member to be. This can easily be looked up, but is provided
  // here to be convenient for LLVM.
  expectedMemberType: ReferenceH[KindH],
  // The type of the resulting reference.
  resultType: ReferenceH[KindH],
  // Member's name, for debug purposes.
  memberName: FullNameH
) extends ExpressionH[KindH] {
//  vassert(resultType.ownership == targetOwnership)
//  vassert(resultType.permission == targetPermission)

  if (resultType.ownership == BorrowH) vassert(resultType.location == YonderH)
  if (resultType.ownership == WeakH) vassert(resultType.location == YonderH)
}

// Produces an array whose size is fixed and known at compile time, and puts it into
// a expressions.
case class NewArrayFromValuesH(
  // The resulting type of the array.
  // TODO: See if we can infer this from the types in the expressions.
  resultType: ReferenceH[StaticSizedArrayTH],
  // The expressions from which we'll get the values to put into the array.
  sourceExpressions: List[ExpressionH[KindH]]
) extends ExpressionH[StaticSizedArrayTH]

// Loads from the "source" expressions and swaps it into the array from arrayExpression at
// the position specified by the integer in indexExpression. The old value from the
// array is moved out into expressionsId.
// This is for the kind of array whose size we know at compile time, the kind that
// doesn't need to carry around a size. For the corresponding instruction for the
// unknown-size-at-compile-time array, see RuntimeSizedArrayStoreH.
case class StaticSizedArrayStoreH(
  // Expression containing the array whose element we'll swap out.
  arrayExpression: ExpressionH[StaticSizedArrayTH],
  // Expression containing the index of the element we'll swap out.
  indexExpression: ExpressionH[IntH],
  // Expression containing the value we'll swap into the array.
  sourceExpression: ExpressionH[KindH],
  resultType: ReferenceH[KindH],
) extends ExpressionH[KindH] {
  vassert(indexExpression.resultType.kind == IntH.i32)
}

// Loads from the "source" expressions and swaps it into the array from arrayExpression at
// the position specified by the integer in indexExpression. The old value from the
// array is moved out into expressionsId.
// This is for the kind of array whose size we don't know at compile time, the kind
// that needs to carry around a size. For the corresponding instruction for the
// known-size-at-compile-time array, see StaticSizedArrayStoreH.
case class RuntimeSizedArrayStoreH(
  // Expression containing the array whose element we'll swap out.
  arrayExpression: ExpressionH[RuntimeSizedArrayTH],
  // Expression containing the index of the element we'll swap out.
  indexExpression: ExpressionH[IntH],
  // Expression containing the value we'll swap into the array.
  sourceExpression: ExpressionH[KindH],
  resultType: ReferenceH[KindH],
) extends ExpressionH[KindH] {
  vassert(indexExpression.resultType.kind == IntH.i32)
}

// Loads from the array in arrayExpression at the index in indexExpression, and stores
// the result in expressionsId. This can never move a reference, only alias it.
// This is for the kind of array whose size we don't know at compile time, the kind
// that needs to carry around a size. For the corresponding instruction for the
// known-size-at-compile-time array, see StaticSizedArrayLoadH.
case class RuntimeSizedArrayLoadH(
  // Expression containing the array whose element we'll read.
  arrayExpression: ExpressionH[RuntimeSizedArrayTH],
  // Expression containing the index of the element we'll read.
  indexExpression: ExpressionH[IntH],
  // The ownership to load as. For example, we might load a constraint reference from a
  // owning Car reference element.
  targetOwnership: OwnershipH,
  // The permission of the resulting reference. This doesn't have to
  // match the ownership of the source reference. For example, we might want
  // to load a constraint reference from an owning local.
  targetPermission: PermissionH,
  expectedElementType: ReferenceH[KindH],
  resultType: ReferenceH[KindH],
) extends ExpressionH[KindH] {
  vassert(indexExpression.resultType.kind == IntH.i32)
//
//  override def resultType: ReferenceH[KindH] = {
//    val location =
//      (targetOwnership, arrayExpression.resultType.kind.rawArray.elementType.location) match {
//        case (BorrowH, _) => YonderH
//        case (OwnH, location) => location
//        case (ShareH, location) => location
//      }
//    ReferenceH(targetOwnership, location, arrayExpression.resultType.kind.rawArray.elementType.kind)
//  }
}

// Loads from the array in arrayExpression at the index in indexExpression, and stores
// the result in expressionsId. This can never move a reference, only alias it.
// This is for the kind of array whose size we know at compile time, the kind that
// doesn't need to carry around a size. For the corresponding instruction for the
// known-size-at-compile-time array, see StaticSizedArrayStoreH.
case class StaticSizedArrayLoadH(
  // Expression containing the array whose element we'll read.
  arrayExpression: ExpressionH[StaticSizedArrayTH],
  // Expression containing the index of the element we'll read.
  indexExpression: ExpressionH[IntH],
  // The ownership to load as. For example, we might load a constraint reference from a
  // owning Car reference element.
  targetOwnership: OwnershipH,
  // The permission of the resulting reference. This doesn't have to
  // match the ownership of the source reference. For example, we might want
  // to load a constraint reference from an owning local.
  targetPermission: PermissionH,
  expectedElementType: ReferenceH[KindH],
  arraySize: Int,
  resultType: ReferenceH[KindH],
) extends ExpressionH[KindH] {
  vassert(indexExpression.resultType.kind == IntH.i32)

//  override def resultType: ReferenceH[KindH] = {
//    val location =
//      (targetOwnership, arrayExpression.resultType.kind.rawArray.elementType.location) match {
//        case (BorrowH, _) => YonderH
//        case (OwnH, location) => location
//        case (ShareH, location) => location
//      }
//    ReferenceH(targetOwnership, location, arrayExpression.resultType.kind.rawArray.elementType.kind)
//  }
}

// Calls a function.
case class CallH(
  // Identifies which function to call.
  function: PrototypeH,
  // Expressions containing the arguments to pass to the function.
  argsExpressions: List[ExpressionH[KindH]]
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = function.returnType
}

// Calls a function defined in some other module.
case class ExternCallH(
  // Identifies which function to call.
  function: PrototypeH,
  // Expressions containing the arguments to pass to the function.
  argsExpressions: List[ExpressionH[KindH]]
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = function.returnType
}

// Calls a function on an interface.
case class InterfaceCallH(
  // Expressions containing the arguments to pass to the function.
  argsExpressions: List[ExpressionH[KindH]],
  // Which parameter has the interface whose table we'll read to get the function.
  virtualParamIndex: Int,
  // The type of the interface.
  // TODO: Take this out, it's redundant, can get it from argsExpressions[virtualParamIndex]
  interfaceRefH: InterfaceRefH,
  // The index in the vtable for the function.
  indexInEdge: Int,
  // The function we expect to be calling. Note that this is the prototype for the abstract
  // function, not the prototype for the function that will eventually be called. The
  // difference is that this prototype will have an interface at the virtualParamIndex'th
  // parameter, and the function that is eventually called will have a struct there.
  functionType: PrototypeH
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = functionType.returnType
  vassert(indexInEdge >= 0)
}

// An if-statement. It will get a boolean from running conditionBlock, and use it to either
// call thenBlock or elseBlock. The result of the thenBlock or elseBlock will be put into
// expressionsId.
case class IfH(
  // The block for the condition. If this results in a true, we'll run thenBlock, otherwise
  // we'll run elseBlock.
  conditionBlock: ExpressionH[BoolH],
  // The block to run if conditionBlock results in a true. The result of this block will be
  // put into expressionsId.
  thenBlock: ExpressionH[KindH],
  // The block to run if conditionBlock results in a false. The result of this block will be
  // put into expressionsId.
  elseBlock: ExpressionH[KindH],

  commonSupertype: ReferenceH[KindH],
) extends ExpressionH[KindH] {
  (thenBlock.resultType.kind, elseBlock.resultType.kind) match {
    case (NeverH(), _) =>
    case (_, NeverH()) =>
    case (a, b) if a == b =>
    case _ => vwat()
  }
  override def resultType: ReferenceH[KindH] = commonSupertype
}

// A while loop. Continuously runs bodyBlock until it returns false.
case class WhileH(
  // The block to run until it returns false.
  bodyBlock: ExpressionH[BoolH]
) extends ExpressionH[StructRefH] {
  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}

// A collection of instructions. The last one will be used as the block's result.
case class ConsecutorH(
  // The instructions to run.
  exprs: List[ExpressionH[KindH]],
) extends ExpressionH[KindH] {
  // We should simplify these away
  vassert(exprs.nonEmpty)

  exprs.init.foreach(nonLastResultLine => {
    // The init ones should never just be VoidLiteralHs, those should be stripped out.
    // Use Hammer.consecutive to conform to this.
    nonLastResultLine match {
      case NewStructH(List(), List(), ReferenceH(_, InlineH, _, _)) => vfail("Should be no creating voids in the middle of a consecutor!")
      case _ =>
    }

    // The init ones should always return void structs.
    // If there's a never somewhere in there, then there should be nothing afterward.
    // Use Hammer.consecutive to conform to this.
    vassert(nonLastResultLine.resultType == ProgramH.emptyTupleStructType)
  })

  val indexOfFirstNever = exprs.indexWhere(_.resultType.kind == NeverH())
  if (indexOfFirstNever >= 0) {
    // The first never should be the last line. There shouldn't be anything after never.
    if (indexOfFirstNever != exprs.size - 1) {
      vfail()
    }
  }

  override def resultType: ReferenceH[KindH] = exprs.last.resultType
}

// An expression where all locals declared inside will be destroyed by the time we exit.
case class BlockH(
  // The instructions to run. This will probably be a consecutor.
  inner: ExpressionH[KindH],
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = inner.resultType
}

// Ends the current function and returns a reference. A function will always end
// with a return statement.
case class ReturnH(
  // The expressions to read from, whose value we'll return from the function.
  sourceExpression: ExpressionH[KindH]
) extends ExpressionH[NeverH] {
  override def resultType: ReferenceH[NeverH] = ReferenceH(ShareH, InlineH, ReadonlyH, NeverH())
}

// Constructs an unknown-size array, whose length is the integer from sizeExpression,
// whose values are generated from the function from generatorExpression. Puts the
// result in a new expressions.
case class ConstructRuntimeSizedArrayH(
  // Expression containing the size of the new array.
  sizeExpression: ExpressionH[IntH],
  // Expression containing the IFunction<int, T> interface reference which we'll
  // call to generate each element of the array.
  // More specifically, we'll call the "__call" function on the interface, which
  // should be the only function on it.
  // This is a constraint reference.
  generatorExpression: ExpressionH[KindH],
  // The prototype for the "__call" function to call on the interface for each element.
  generatorMethod: PrototypeH,

  elementType: ReferenceH[KindH],
  // The resulting type of the array.
  // TODO: Remove this, it's redundant with the generatorExpression's interface's
  // only method's return type.
  resultType: ReferenceH[RuntimeSizedArrayTH]
) extends ExpressionH[RuntimeSizedArrayTH] {
  vassert(
    generatorExpression.resultType.ownership == BorrowH ||
      generatorExpression.resultType.ownership == ShareH)
  vassert(sizeExpression.resultType.kind == IntH.i32)
}

// Constructs an unknown-size array, whose length is the integer from sizeExpression,
// whose values are generated from the function from generatorExpression. Puts the
// result in a new expressions.
case class StaticArrayFromCallableH(
  // Expression containing the IFunction<int, T> interface reference which we'll
  // call to generate each element of the array.
  // More specifically, we'll call the "__call" function on the interface, which
  // should be the only function on it.
  // This is a constraint reference.
  generatorExpression: ExpressionH[KindH],
  // The prototype for the "__call" function to call on the interface for each element.
  generatorMethod: PrototypeH,

  elementType: ReferenceH[KindH],
  // The resulting type of the array.
  // TODO: Remove this, it's redundant with the generatorExpression's interface's
  // only method's return type.
  resultType: ReferenceH[StaticSizedArrayTH]
) extends ExpressionH[StaticSizedArrayTH] {
  vassert(
    generatorExpression.resultType.ownership == BorrowH ||
      generatorExpression.resultType.ownership == ShareH)
}

// Destroys an array previously created with NewArrayFromValuesH.
case class DestroyStaticSizedArrayIntoFunctionH(
  // Expression containing the array we'll destroy.
  // This is an owning reference.
  arrayExpression: ExpressionH[StaticSizedArrayTH],
  // Expression containing the IFunction<T, Void> interface reference which we'll
  // call to destroy each element of the array.
  // More specifically, we'll call the "__call" function on the interface, which
  // should be the only function on it.
  // This is a constraint reference.
  consumerExpression: ExpressionH[InterfaceRefH],
  // The prototype for the "__call" function to call on the interface for each element.
  consumerMethod: PrototypeH,
  arrayElementType: ReferenceH[KindH],
  arraySize: Int
) extends ExpressionH[StructRefH] {
  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}

// Destroys an array previously created with ConstructRuntimeSizedArrayH.
case class DestroyRuntimeSizedArrayH(
  // Expression containing the array we'll destroy.
  // This is an owning reference.
  arrayExpression: ExpressionH[RuntimeSizedArrayTH],
  // Expression containing the IFunction<T, Void> interface reference which we'll
  // call to destroy each element of the array.
  // More specifically, we'll call the "__call" function on the interface, which
  // should be the only function on it.
  // This is a constraint reference.
  consumerExpression: ExpressionH[InterfaceRefH],
  // The prototype for the "__call" function to call on the interface for each element.
  consumerMethod: PrototypeH,
  arrayElementType: ReferenceH[KindH],
) extends ExpressionH[StructRefH] {
  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}

// Creates a new struct instance.
case class NewStructH(
  // Expressions containing the values we'll use as members of the new struct.
  sourceExpressions: List[ExpressionH[KindH]],
  // Names of the members of the struct, in order.
  targetMemberNames: List[FullNameH],
  // The type of struct we'll create.
  resultType: ReferenceH[StructRefH]
) extends ExpressionH[StructRefH]

// Gets the length of an unknown-sized array.
case class ArrayLengthH(
  // Expression containing the array whose length we'll get.
  sourceExpression: ExpressionH[KindH],
) extends ExpressionH[IntH] {
  override def resultType: ReferenceH[IntH] = ReferenceH(ShareH, InlineH, ReadonlyH, IntH.i32)
}

// Turns a constraint ref into a weak ref.
case class WeakAliasH(
  // Expression containing the constraint reference to turn into a weak ref.
  refExpression: ExpressionH[KindH],
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = ReferenceH(WeakH, YonderH, refExpression.resultType.permission, refExpression.resultType.kind)
}

// Checks if the given args are the same instance.
case class IsSameInstanceH(
  leftExpression: ExpressionH[KindH],
  rightExpression: ExpressionH[KindH],
) extends ExpressionH[KindH] {
  override def resultType: ReferenceH[KindH] = ReferenceH(ShareH, InlineH, ReadonlyH, BoolH())
}

// Tries to downcast to the specified subtype and wrap in a Some.
// If it fails, will result in a None.
case class AsSubtypeH(
  // Expression whose result we'll try to downcast
  sourceExpression: ExpressionH[KindH],
  // The subtype to try and cast the source to.
  targetType: KindH,
  // Should be an owned ref to optional of something
  resultType: ReferenceH[InterfaceRefH],
  // Function to give a ref to to make a Some(ref)
  someConstructor: PrototypeH,
  // Function to make a None of the right type
  noneConstructor: PrototypeH,
) extends ExpressionH[KindH]

// Locks a weak ref to turn it into an optional of borrow ref.
case class LockWeakH(
  // Expression containing the array whose length we'll get.
  sourceExpression: ExpressionH[KindH],
  // Should be an owned ref to optional of borrow ref of something
  resultType: ReferenceH[InterfaceRefH],
  // Function to give a borrow ref to to make a Some(borrow ref)
  someConstructor: PrototypeH,
  // Function to make a None of the right type
  noneConstructor: PrototypeH,
) extends ExpressionH[KindH]

// Only used for the VM, or a debug mode. Checks that the reference count
// is as we expected.
// This instruction can be safely ignored, it's mainly here for tests.
case class CheckRefCountH(
  // Expression containing the reference whose ref count we'll measure.
  refExpression: ExpressionH[KindH],
  // The type of ref count to check.
  category: RefCountCategory,
  // Expression containing a number, so we can assert it's equal to the object's
  // ref count.
  numExpression: ExpressionH[IntH]
) extends ExpressionH[StructRefH] {
  vassert(numExpression.resultType.kind == IntH.i32)

  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}
// The type of ref count that an object might have. Used with the CheckRefCountH
// instruction for counting how many references of a certain type there are.
sealed trait RefCountCategory
// Used to count how many variables are refering to an object.
case object VariableRefCount extends RefCountCategory
// Used to count how many members are refering to an object.
case object MemberRefCount extends RefCountCategory
// Used to count how many arguments are refering to an object.
case object ArgumentRefCount extends RefCountCategory
// Used to count how many registers are refering to an object.
case object RegisterRefCount extends RefCountCategory

// See DINSIE for why this isn't three instructions, and why we don't have the
// destructor prototype in it.
case class DiscardH(sourceExpression: ExpressionH[KindH]) extends ExpressionH[StructRefH] {
  sourceExpression.resultType.ownership match {
    case BorrowH | ShareH | WeakH =>
  }
  override def resultType: ReferenceH[StructRefH] = ProgramH.emptyTupleStructType
}

trait IExpressionH {
  def expectReferenceExpression(): ReferenceExpressionH = {
    this match {
      case r @ ReferenceExpressionH(_) => r
      case AddressExpressionH(_) => vfail("Expected a reference as a result, but got an address!")
    }
  }
  def expectAddressExpression(): AddressExpressionH = {
    this match {
      case a @ AddressExpressionH(_) => a
      case ReferenceExpressionH(_) => vfail("Expected an address as a result, but got a reference!")
    }
  }
}
case class ReferenceExpressionH(reference: ReferenceH[KindH]) extends IExpressionH
case class AddressExpressionH(reference: ReferenceH[KindH]) extends IExpressionH

// Identifies a local variable.
case class Local(
  // No two variables in a FunctionH have the same id.
  id: VariableIdH,

  // Whether the local is ever changed or not.
  variability: Variability,

  // The type of the reference this local variable has.
  typeH: ReferenceH[KindH],

  // Usually filled by catalyst, for Midas' benefit. Used in HGM.
  keepAlive: Boolean)

case class VariableIdH(
  // Just to uniquify VariableIdH instances. No two variables in a FunctionH will have
  // the same number.
  number: Int,
  // Where the variable is relative to the stack frame's beginning.
  height: Int,
  // Just for debugging purposes
  name: Option[FullNameH]) {
}
