#include <iostream>
#include "function/expressions/shared/branch.h"
#include "function/expressions/shared/elements.h"
#include "function/expressions/shared/controlblock.h"
#include "function/expressions/shared/members.h"
#include "function/expressions/shared/heap.h"

#include "translatetype.h"

#include "expressions/expressions.h"
#include "expressions/shared/shared.h"
#include "expressions/shared/members.h"
#include "expression.h"

std::vector<LLVMValueRef> translateExpressions(
    GlobalState* globalState,
    FunctionState* functionState,
    LLVMBuilderRef builder,
    std::vector<Expression*> exprs) {
  auto result = std::vector<LLVMValueRef>{};
  result.reserve(exprs.size());
  for (auto expr : exprs) {
    result.push_back(
        translateExpression(globalState, functionState, builder, expr));
  }
  return result;
}

LLVMValueRef translateExpression(
    GlobalState* globalState,
    FunctionState* functionState,
    LLVMBuilderRef builder,
    Expression* expr) {
  buildFlare(FL(), globalState, builder, typeid(*expr).name());
  if (auto constantI64 = dynamic_cast<ConstantI64*>(expr)) {
    // See ULTMCIE for why we load and store here.
    return makeConstIntExpr(builder, LLVMInt64Type(), constantI64->value);
  } else if (auto constantBool = dynamic_cast<ConstantBool*>(expr)) {
    // See ULTMCIE for why this is an add.
    return makeConstIntExpr(builder, LLVMInt1Type(), constantBool->value);
  } else if (auto discardM = dynamic_cast<Discard*>(expr)) {
    return translateDiscard(globalState, functionState, builder, discardM);
  } else if (auto ret = dynamic_cast<Return*>(expr)) {
    return LLVMBuildRet(
        builder,
        translateExpression(
            globalState, functionState, builder, ret->sourceExpr));
  } else if (auto stackify = dynamic_cast<Stackify*>(expr)) {
    auto valueToStore =
        translateExpression(
            globalState, functionState, builder, stackify->sourceExpr);
    makeLocal(
        globalState, functionState, builder, stackify->local, valueToStore);
    return makeNever();
  } else if (auto localStore = dynamic_cast<LocalStore*>(expr)) {
    // The purpose of LocalStore is to put a swap value into a local, and give
    // what was in it.
    auto localAddr = functionState->getLocalAddr(*localStore->local->id);
    auto oldValue =
        LLVMBuildLoad(builder, localAddr, localStore->localName.c_str());
    auto valueToStore =
        translateExpression(
            globalState, functionState, builder, localStore->sourceExpr);
    LLVMBuildStore(builder, valueToStore, localAddr);
    return oldValue;
  } else if (auto localLoad = dynamic_cast<LocalLoad*>(expr)) {
    if (localLoad->local->type->location == Location::INLINE) {
      auto localAddr = functionState->getLocalAddr(*localLoad->local->id);
      return LLVMBuildLoad(builder, localAddr, localLoad->localName.c_str());
    } else {
      auto localAddr = functionState->getLocalAddr(*localLoad->local->id);
      auto ptrLE =
          LLVMBuildLoad(builder, localAddr, localLoad->localName.c_str());
      adjustRc(AFL("LocalLoad"), globalState, builder, ptrLE, localLoad->local->type, 1);
      return ptrLE;
    }
  } else if (auto unstackify = dynamic_cast<Unstackify*>(expr)) {
    // The purpose of Unstackify is to destroy the local and give what was in
    // it, but in LLVM there's no instruction (or need) for destroying a local.
    // So, we just give what was in it. It's ironically identical to LocalLoad.
    auto localAddr = functionState->getLocalAddr(*unstackify->local->id);
    return LLVMBuildLoad(builder, localAddr, "");
  } else if (auto call = dynamic_cast<Call*>(expr)) {
    return translateCall(globalState, functionState, builder, call);
  } else if (auto externCall = dynamic_cast<ExternCall*>(expr)) {
    return translateExternCall(globalState, functionState, builder, externCall);
  } else if (auto argument = dynamic_cast<Argument*>(expr)) {
    return LLVMGetParam(functionState->containingFunc, argument->argumentIndex);
  } else if (auto constantStr = dynamic_cast<ConstantStr*>(expr)) {
    return translateConstantStr(FL(), globalState, builder, constantStr);
  } else if (auto newStruct = dynamic_cast<NewStruct*>(expr)) {
    auto memberExprs =
        translateExpressions(
            globalState, functionState, builder, newStruct->sourceExprs);
    return translateConstruct(
        AFL("NewStruct"), globalState, builder, newStruct->resultType, memberExprs);
  } else if (auto block = dynamic_cast<Block*>(expr)) {
    auto exprs =
        translateExpressions(globalState, functionState, builder, block->exprs);
    assert(!exprs.empty());
    return exprs.back();
  } else if (auto iff = dynamic_cast<If*>(expr)) {
    return translateIf(globalState, functionState, builder, iff);
  } else if (auto whiile = dynamic_cast<While*>(expr)) {
    return translateWhile(globalState, functionState, builder, whiile);
  } else if (auto destructureM = dynamic_cast<Destroy*>(expr)) {
    return translateDestructure(globalState, functionState, builder, destructureM);
  } else if (auto memberLoad = dynamic_cast<MemberLoad*>(expr)) {
    auto structExpr =
        translateExpression(
            globalState, functionState, builder, memberLoad->structExpr);
    auto mutability = ownershipToMutability(memberLoad->structType->ownership);
    auto memberIndex = memberLoad->memberIndex;
    auto memberName = memberLoad->memberName;
    auto resultLE =
        loadMember(
            AFL("MemberLoad"),
            globalState,
            builder,
            memberLoad->structType,
            structExpr,
            mutability,
            memberLoad->expectedResultType,
            memberIndex,
            memberName);
    discard(
        AFL("MemberLoad drop struct"),
        globalState, functionState, builder, memberLoad->structType, structExpr);
    return resultLE;
  } else if (auto destroyKnownSizeArrayIntoFunction = dynamic_cast<DestroyKnownSizeArrayIntoFunction*>(expr)) {
    auto consumerType = destroyKnownSizeArrayIntoFunction->consumerType;
    auto arrayReferend = destroyKnownSizeArrayIntoFunction->arrayReferend;
    auto arrayExpr = destroyKnownSizeArrayIntoFunction->arrayExpr;
    auto consumerExpr = destroyKnownSizeArrayIntoFunction->consumerExpr;
    auto arrayType = destroyKnownSizeArrayIntoFunction->arrayType;

    auto arrayWrapperLE = translateExpression(globalState, functionState, builder, arrayExpr);
    auto arrayPtrLE = getKnownSizeArrayContentsPtr(builder, arrayWrapperLE);

    auto consumerLE = translateExpression(globalState, functionState, builder, consumerExpr);

    foreachArrayElement(
        functionState, builder, LLVMConstInt(LLVMInt64Type(), arrayReferend->size, false), arrayPtrLE,
        [globalState, consumerType, arrayPtrLE, consumerLE](LLVMValueRef indexLE, LLVMBuilderRef bodyBuilder) {
          acquireReference(AFL("DestroyKSAIntoF consume iteration"), globalState, bodyBuilder, consumerType, consumerLE);

          std::vector<LLVMValueRef> indices = { constI64LE(0), indexLE };
          auto elementPtrLE = LLVMBuildGEP(bodyBuilder, arrayPtrLE, indices.data(), indices.size(), "elementPtr");
          auto elementLE = LLVMBuildLoad(bodyBuilder, elementPtrLE, "element");
          std::vector<LLVMValueRef> argExprsLE = { consumerLE, elementLE };
          buildInterfaceCall(bodyBuilder, argExprsLE, 0, 0);
        });

    if (arrayType->ownership == Ownership::OWN) {
      adjustRc(AFL("Destroy decrementing the owning ref"), globalState, builder, arrayWrapperLE, arrayType, -1);
    } else if (arrayType->ownership == Ownership::SHARE) {
      // We dont decrement anything here, we're only here because we already hit zero.
    } else {
      assert(false);
    }

    freeConcrete(AFL("DestroyKSAIntoF"), globalState, functionState, builder,
        arrayWrapperLE, arrayType);

    discard(AFL("DestroyKSAIntoF"), globalState, functionState, builder, consumerType, consumerLE);

    return makeNever();
  } else if (auto destroyUnknownSizeArrayIntoFunction = dynamic_cast<DestroyUnknownSizeArray*>(expr)) {
    auto consumerType = destroyUnknownSizeArrayIntoFunction->consumerType;
    auto arrayReferend = destroyUnknownSizeArrayIntoFunction->arrayReferend;
    auto arrayExpr = destroyUnknownSizeArrayIntoFunction->arrayExpr;
    auto consumerExpr = destroyUnknownSizeArrayIntoFunction->consumerExpr;
    auto arrayType = destroyUnknownSizeArrayIntoFunction->arrayType;

    auto arrayWrapperLE = translateExpression(globalState, functionState, builder, arrayExpr);
    auto arrayPtrLE = getUnknownSizeArrayContentsPtr(builder, arrayWrapperLE);
    auto arrayLenLE = getUnknownSizeArrayLength(builder, arrayPtrLE);

    auto consumerLE = translateExpression(globalState, functionState, builder, consumerExpr);

    foreachArrayElement(
        functionState, builder, arrayLenLE, arrayPtrLE,
        [globalState, consumerType, arrayPtrLE, consumerLE](LLVMValueRef indexLE, LLVMBuilderRef bodyBuilder) {
          acquireReference(AFL("DestroyUSAIntoF consume iteration"), globalState, bodyBuilder, consumerType, consumerLE);

          std::vector<LLVMValueRef> indices = { constI64LE(0), indexLE };
          auto elementPtrLE = LLVMBuildGEP(bodyBuilder, arrayPtrLE, indices.data(), indices.size(), "elementPtr");
          auto elementLE = LLVMBuildLoad(bodyBuilder, elementPtrLE, "element");
          std::vector<LLVMValueRef> argExprsLE = { consumerLE, elementLE };
          buildInterfaceCall(bodyBuilder, argExprsLE, 0, 0);
        });

    if (arrayType->ownership == Ownership::OWN) {
      adjustRc(AFL("Destroy decrementing the owning ref"), globalState, builder, arrayWrapperLE, arrayType, -1);
    } else if (arrayType->ownership == Ownership::SHARE) {
      // We dont decrement anything here, we're only here because we already hit zero.
    } else {
      assert(false);
    }

    freeConcrete(AFL("DestroyUSAIntoF"), globalState, functionState, builder,
        arrayWrapperLE, arrayType);

    discard(AFL("DestroyUSAIntoF"), globalState, functionState, builder, consumerType, consumerLE);

    return makeNever();
  } else if (auto knownSizeArrayLoad = dynamic_cast<KnownSizeArrayLoad*>(expr)) {
    auto arrayType = knownSizeArrayLoad->arrayType;
    auto arrayExpr = knownSizeArrayLoad->arrayExpr;
    auto indexExpr = knownSizeArrayLoad->indexExpr;

    auto arrayWrapperPtrLE = translateExpression(globalState, functionState, builder, arrayExpr);
    auto sizeLE = constI64LE(dynamic_cast<KnownSizeArrayT*>(knownSizeArrayLoad->arrayType->referend)->size);
    auto indexLE = translateExpression(globalState, functionState, builder, indexExpr);
    auto mutability = ownershipToMutability(arrayType->ownership);
    discard(AFL("KSALoad"), globalState, functionState, builder, arrayType, arrayWrapperPtrLE);

    LLVMValueRef arrayPtrLE = getKnownSizeArrayContentsPtr(builder, arrayWrapperPtrLE);
    return loadElement(globalState, functionState, builder, arrayType, sizeLE, arrayPtrLE, mutability, indexLE);
  } else if (auto unknownSizeArrayLoad = dynamic_cast<UnknownSizeArrayLoad*>(expr)) {
    auto arrayType = unknownSizeArrayLoad->arrayType;
    auto arrayExpr = unknownSizeArrayLoad->arrayExpr;
    auto indexExpr = unknownSizeArrayLoad->indexExpr;

    auto arrayWrapperPtrLE = translateExpression(globalState, functionState, builder, arrayExpr);
    auto sizeLE = getUnknownSizeArrayLength(builder, arrayWrapperPtrLE);
    auto indexLE = translateExpression(globalState, functionState, builder, indexExpr);
    auto mutability = ownershipToMutability(arrayType->ownership);
    discard(AFL("USALoad"), globalState, functionState, builder, arrayType, arrayWrapperPtrLE);

    LLVMValueRef arrayPtrLE = getUnknownSizeArrayContentsPtr(builder, arrayWrapperPtrLE);
    return loadElement(globalState, functionState, builder, arrayType, sizeLE, arrayPtrLE, mutability, indexLE);
  } else if (auto arrayLength = dynamic_cast<ArrayLength*>(expr)) {
    auto arrayType = arrayLength->sourceType;
    auto arrayExpr = arrayLength->sourceExpr;
//    auto indexExpr = arrayLength->indexExpr;

    auto arrayWrapperPtrLE = translateExpression(globalState, functionState, builder, arrayExpr);
    auto sizeLE = getUnknownSizeArrayLength(builder, arrayWrapperPtrLE);
    discard(AFL("USALen"), globalState, functionState, builder, arrayType, arrayWrapperPtrLE);

    return sizeLE;
  } else if (auto newArrayFromValues = dynamic_cast<NewArrayFromValues*>(expr)) {
    return translateNewArrayFromValues(globalState, functionState, builder, newArrayFromValues);
  } else if (auto constructUnknownSizeArray = dynamic_cast<ConstructUnknownSizeArray*>(expr)) {
    return translateConstructUnknownSizeArray(globalState, functionState, builder, constructUnknownSizeArray);
  } else if (auto interfaceCall = dynamic_cast<InterfaceCall*>(expr)) {
    return translateInterfaceCall(
        globalState, functionState, builder, interfaceCall);
  } else if (auto memberStore = dynamic_cast<MemberStore*>(expr)) {
    auto sourceExpr =
        translateExpression(
            globalState, functionState, builder, memberStore->sourceExpr);
    auto structExpr =
        translateExpression(
            globalState, functionState, builder, memberStore->structExpr);
    auto structReferend =
        dynamic_cast<StructReferend*>(memberStore->structType->referend);
    auto structDefM = globalState->program->getStruct(structReferend->fullName);
    auto memberIndex = memberStore->memberIndex;
    auto memberName = memberStore->memberName;
    auto oldMemberLE =
        swapMember(
            builder, structDefM, structExpr, memberIndex, memberName, sourceExpr);
    discard(
        AFL("MemberStore discard struct"), globalState, functionState, builder,
        memberStore->structType, structExpr);
    return oldMemberLE;
  } else if (auto structToInterfaceUpcast =
      dynamic_cast<StructToInterfaceUpcast*>(expr)) {
    auto sourceLE =
        translateExpression(
            globalState, functionState, builder, structToInterfaceUpcast->sourceExpr);

    // If it was inline before, upgrade it to a yonder struct.
    // This however also means that small imm virtual params must be pointers,
    // and value-ify themselves immediately inside their bodies.
    // If the receiver expects a yonder, then they'll assume its on the heap.
    // But if receiver expects an inl, its in a register.
    // But we can only interfacecall with a yonder.
    // So we need a thunk to receive that yonder, copy it, fire it into the
    // real function.
    // fuck... thunks. didnt want to do that.

    // alternative:
    // what if we made it so someone receiving an override of an imm inl interface
    // just takes in that much memory? it really just means a bit of wasted stack
    // space, but it means we wouldnt need any thunking.
    // It also means we wouldnt need any heap allocating.
    // So, the override function will receive the entire interface, and just
    // assume that the right thing is in there.
    // Any callers will also have to wrap in an interface. but theyre copying
    // anyway so should be fine.

    // alternative:
    // only inline primitives. Which cant have interfaces anyway.
    // maybe the best solution for now?

    // maybe function params that are inl can take a pointer, and they can
    // just copy it immediately?

    assert(structToInterfaceUpcast->sourceStructType->location != Location::INLINE);

    auto interfaceRefLT =
        globalState->getInterfaceRefStruct(
            structToInterfaceUpcast->targetInterfaceRef->fullName);

    auto interfaceRefLE = LLVMGetUndef(interfaceRefLT);
    interfaceRefLE =
        LLVMBuildInsertValue(
            builder,
            interfaceRefLE,
            getControlBlockPtr(builder, sourceLE, structToInterfaceUpcast->sourceStructType),
            0,
            "interfaceRefWithOnlyObj");
    interfaceRefLE =
        LLVMBuildInsertValue(
            builder,
            interfaceRefLE,
            globalState->getInterfaceTablePtr(
                globalState->program->getStruct(
                    structToInterfaceUpcast->sourceStructId->fullName)
                    ->getEdgeForInterface(structToInterfaceUpcast->targetInterfaceRef->fullName)),
            1,
            "interfaceRef");
    return interfaceRefLE;
  } else {
    std::string name = typeid(*expr).name();
    std::cout << name << std::endl;
    assert(false);
  }
  assert(false);
}