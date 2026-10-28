# 保留运行时注解及其默认值
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# 保留注解类本身
-keep @interface com.sd.lib.singlecache.SingleCacheEntity

# 保留被该注解标注的类及其所有成员
-keep @com.sd.lib.singlecache.SingleCacheEntity class * {*;}

# 保留实体的直接父类及其所有成员，更上层的父类由接入方保留
-if @com.sd.lib.singlecache.SingleCacheEntity class * extends **
-keep class <2> {*;}
