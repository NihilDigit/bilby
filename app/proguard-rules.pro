# R8(full mode,AGP 8 起是默认)。
#
# 这个文件很短,是查过的结果而不是省事:项目里所有会被 R8 破坏的库都自带 consumer rules,
# 已经随 aar/jar 合并进来了,再抄一遍只会掩盖将来它们规则变化。逐个说明:
#
#   kotlinx.serialization  自带 META-INF/com.android.tools/r8/kotlinx-serialization-r8.pro,
#                          保住了 @Serializable 类的注解、Companion、serializer()、
#                          object 的 INSTANCE,以及 $$serializer.descriptor 不被优化。
#                          我们的 @Serializable 全是普通 data class / data object,没有
#                          多态、没有自定义 serializer、没有按名字查类,落在它的覆盖范围内。
#   Room                   自带 -keep class * extends RoomDatabase { void <init>(); }
#   OkHttp / Coil3 / Media3 均自带。
#   ZXing core             纯计算,不反射。
#
# 我们自己的代码里没有任何按名字取类或取成员的地方(Class.forName、getDeclaredX、
# 字符串反射都没有;`X::class.java` 是编译期常量引用,R8 会跟着改名)。所以**没有**
# 需要手写的 -keep。新增按名字反射的代码时,规则要连同那段代码一起加,并在这里记一笔。

# 崩溃栈能读。R8 默认把源文件名混淆掉,行号也会跟着没意义;这两条把行号表留下,
# 再把文件名统一改写成 "SourceFile"(不泄露原始文件名,但 logcat 里仍能定位到行)。
# 对应的 mapping 文件在 app/build/outputs/mapping/release/mapping.txt,
# 换版本会被覆盖,要留证据自己拷走。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
