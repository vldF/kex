package org.jetbrains.research.kex.descriptor

import org.jetbrains.research.kex.asm.util.Visibility
import org.jetbrains.research.kex.asm.util.visibility
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.ktype.KexRtManager.rtMapped
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.type.SystemTypeNames
import org.jetbrains.research.kthelper.`try`

private val visibilityLevel by lazy {
    kexConfig.getEnumValue("apiGeneration", "visibility", true, Visibility.PUBLIC)
}

object ConcreteInstanceGenerator {
    private val concreteInstanceInfo = with(SystemTypeNames) {
        mutableMapOf(
            collectionClass to setOf(arrayListClass.rtMapped),
            listClass to setOf(arrayListClass.rtMapped),
            queueClass to setOf(arrayListClass.rtMapped),
            dequeClass to setOf(arrayDequeClass.rtMapped),
            setClass to setOf(hashSetClass.rtMapped),
            sortedSetClass to setOf(treeSetClass.rtMapped),
            navigableSetClass to setOf(treeSetClass.rtMapped),
            mapClass to setOf(hashMapClass.rtMapped),
            sortedMapClass to setOf(treeSetClass.rtMapped),
            navigableMapClass to setOf(treeSetClass.rtMapped)
        )
    }

    operator fun get(klass: Class) = `try` {
        val newKlass = concreteInstanceInfo.getOrElse(klass.fullName) {
            klass.cm.concreteClasses.filter {
                klass.isAncestorOf(it) && it.isInstantiable && visibilityLevel <= it.visibility
            }.map {
                it.fullName
            }
        }.random()
        klass.cm[newKlass]
    }.getOrElse {
        throw NoConcreteInstanceException(klass)
    }
}