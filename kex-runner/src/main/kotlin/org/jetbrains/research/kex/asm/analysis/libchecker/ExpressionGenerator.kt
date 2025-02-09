package org.jetbrains.research.kex.asm.analysis.libchecker

import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.BasicBlock
import org.jetbrains.research.kfg.ir.BodyBlock
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.EmptyUsageContext
import org.jetbrains.research.kfg.ir.value.Value
import org.jetbrains.research.kfg.ir.value.instruction.*
import org.jetbrains.research.kfg.ir.value.usageContext
import org.jetbrains.research.kfg.type.Type
import org.jetbrains.research.libsl.asg.*


class ExpressionGenerator(
    val method: Method?,
    val klass: Class,
    val cm: ClassManager,
    private val syntheticContexts: Map<String, SyntheticContext>
): ExpressionVisitor<Value>() {
    val expressionBlocks = mutableListOf(BodyBlock("synthesized expression"))
    val oldCallStorage = mutableListOf<Value>()

    private val usageContext = method?.usageContext ?: EmptyUsageContext
    private val constructorUsageContext = klass.constructors.first().usageContext
    private val instructionFactory = cm.instruction
    private val valueFactory = cm.value
    private val syntheticContext = syntheticContexts[klass.fullName]
        ?: error("unresolved automaton ${klass.fullName}")

    private fun equalsWithObject(obj: Value, objKlass: Class, other: Value): CallInst {
        val equalsMethod = objKlass.allMethods.firstOrNull { method ->
            method.name == "equals" && method.argTypes.size == 1 && method.argTypes.first() == cm.type.objectType
        } ?: error("cannot find equals method for $objKlass")
        val args = arrayOf(
            other
        )

        return instructionFactory.getCall(usageContext, CallOpcode.VIRTUAL, equalsMethod, equalsMethod.klass, obj, args, true)
    }

    override fun visitAccessAlias(node: AccessAlias): Value {
        return visit(node.childAccess!!)
    }

    override fun visitArrayAccess(node: ArrayAccess): Value {
        return TODO()
    }

    override fun visitAutomatonGetter(node: AutomatonGetter): Value {
        return TODO()
    }

    override fun visitAutomatonVariableDeclaration(node: AutomatonVariableDeclaration): Value {
        val automaton = node.automaton
        val field = syntheticContexts[automaton.name]?.fields?.get(node)
            ?: error("unresolved variable ${node.fullName}")
        val klass = method?.klass ?: error("klass can't be null")
        val `this` = valueFactory.getThis(klass)
        return instructionFactory.getFieldLoad(usageContext, `this`, field).also {
            expressionBlocks.last().add(it)
        }
    }

    override fun visitBinaryOpExpression(node: BinaryOpExpression): Value {
        val opcode = node.op.opcode
        val left = visit(node.left)
        val right = visit(node.right)
        return if (opcode != null) {
            instructionFactory.getBinary(usageContext, opcode, left, right).also {
                expressionBlocks.last().add(it)
            }
        } else {
            // this is equality operation
            val res = if (!left.type.isPrimary) {
                equalsWithObject(left, cm[left.type.name], right)
            } else if (!right.type.isPrimary) {
                equalsWithObject(right, cm[right.type.name], left)
            } else {
                val cond = instructionFactory.getCmp(usageContext, cm.type.boolType, node.op.comparisonOpCode, left, right)
                return expandCompOp(cond)
            }

            res.also {
                expressionBlocks.last().add(it)
            }
        }
    }

    private fun expandCompOp(cond: Instruction): Instruction {
        val incomingMap = mutableMapOf<BasicBlock, Value>()
        val lastBlock = expressionBlocks.last()
        val phiBlock = BodyBlock("phiBranch").apply {
            expressionBlocks.add(this)
            method?.add(usageContext, this)
        }
        val trueBlock = BodyBlock("trueBlock").apply {
            add(
                instructionFactory.getJump(usageContext, phiBlock)
            )
            incomingMap[this] = valueFactory.trueConstant
            method?.add(usageContext, this)

            addPredecessor(usageContext, lastBlock)
            lastBlock.addSuccessor(usageContext, this)

            addSuccessor(usageContext, phiBlock)
            phiBlock.addPredecessor(usageContext, this)
        }
        val falseBlock = BodyBlock("falseBlock").apply {
            add(
                instructionFactory.getJump(usageContext, phiBlock)
            )
            incomingMap[this] = valueFactory.falseConstant
            method?.add(usageContext, this)

            addPredecessor(usageContext, lastBlock)
            lastBlock.addSuccessor(usageContext, this)

            addSuccessor(usageContext, phiBlock)
            phiBlock.addPredecessor(usageContext, this)
        }
        lastBlock.add(
            cond
        )
        lastBlock.add(
            instructionFactory.getBranch(usageContext, cond, trueBlock, falseBlock)
        )
        return instructionFactory.getPhi(usageContext, cm.type.boolType, incomingMap).also {
            phiBlock.add(it)
        }
    }

    private val ArithmeticBinaryOps.opcode: BinaryOpcode?
        get() = when (this) {
            ArithmeticBinaryOps.ADD -> BinaryOpcode.ADD
            ArithmeticBinaryOps.SUB -> BinaryOpcode.SUB
            ArithmeticBinaryOps.MUL -> BinaryOpcode.MUL
            ArithmeticBinaryOps.DIV -> BinaryOpcode.DIV
            ArithmeticBinaryOps.AND -> BinaryOpcode.AND
            ArithmeticBinaryOps.OR -> BinaryOpcode.OR
            ArithmeticBinaryOps.XOR -> BinaryOpcode.XOR
            ArithmeticBinaryOps.MOD -> BinaryOpcode.DIV
            ArithmeticBinaryOps.EQ -> null
            ArithmeticBinaryOps.NOT_EQ -> null
            ArithmeticBinaryOps.GT -> null
            ArithmeticBinaryOps.GT_EQ -> null
            ArithmeticBinaryOps.LT -> null
            ArithmeticBinaryOps.LT_EQ -> null
        }

    private val ArithmeticBinaryOps.comparisonOpCode
        get() = when(this) {
            ArithmeticBinaryOps.EQ -> CmpOpcode.EQ
            ArithmeticBinaryOps.NOT_EQ -> CmpOpcode.NEQ
            ArithmeticBinaryOps.GT -> CmpOpcode.GT
            ArithmeticBinaryOps.GT_EQ -> CmpOpcode.GE
            ArithmeticBinaryOps.LT -> CmpOpcode.LT
            ArithmeticBinaryOps.LT_EQ -> CmpOpcode.LE
            else -> error("unknown op: $this")
        }

    override fun visitBool(node: BoolLiteral): Value {
        return valueFactory.getBool(node.value)
    }

    override fun visitCallAutomatonConstructor(node: CallAutomatonConstructor): Value {
        return TODO()
    }

    override fun visitConstructorArgument(node: ConstructorArgument): Value {
        return TODO()
    }

    override fun visitFloatNumber(node: FloatLiteral): Value {
        return valueFactory.getFloat(node.value)
    }

    override fun visitFunctionArgument(node: FunctionArgument): Value {
        return syntheticContext.methodsArgs[method]?.get(node.index) ?: error("unresolved argument #${node.index}")
    }

    override fun visitGlobalVariableDeclaration(node: GlobalVariableDeclaration): Value {
        return TODO()
    }

    override fun visitIntegerNumber(node: IntegerLiteral): Value {
        return valueFactory.getInt(node.value)
    }

    override fun visitOldValue(node: OldValue): Value {
        return visit(node.value).also {
            oldCallStorage.add(it)
        }
    }

    override fun visitRealTypeAccess(node: RealTypeAccess): Value {
        error("unsupported operation realTypeAccess")
    }

    override fun visitResultVariable(node: ResultVariable): Value {
        val returnStatement = method?.bodyBlocks?.flatMap { it.instructions }?.firstOrNull { it is ReturnInst }
            ?: error("return statement not found in the original method or method is null")

        return returnStatement.operands[0]
    }

    override fun visitStringValue(node: StringLiteral): Value {
        return valueFactory.getString(node.value)
    }

    override fun visitUnaryOpExpression(node: UnaryOpExpression): Value {
        return instructionFactory.getUnary(usageContext, UnaryOpcode.NEG, visit(node.value)).also {
            expressionBlocks.last().add(it)
        }
    }

    override fun visitVariableAccess(node: VariableAccess): Value {
        val variable = node.variable
        return if (variable != null) {
            if (variable is FunctionArgument) {
                if (method == null) error("function argument ${variable.fullName} accessed not in function")
                valueFactory.getArgument(variable.index, method, variable.type.kfgType(cm))
            } else {
                val field = syntheticContext.fields[node.variable] ?: error("unknown variable ${node.variable!!.name}")
                val `this` = valueFactory.getThis(method?.klass ?: error("method is empty"))
                instructionFactory.getFieldLoad(constructorUsageContext, `this`, field).also {
                    expressionBlocks.last().add(it)
                }
            }
        } else {
            error("chain call")
        }
    }
}