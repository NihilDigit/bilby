# kotlinx.serialization 生成的 serializer 靠反射查找伴生对象,R8 需保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
