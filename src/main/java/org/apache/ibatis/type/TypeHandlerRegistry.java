/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.chrono.JapaneseDate;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TypeHandler 注册表，相当于管理 TypeHandler 的容器
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

  /**
   * 1.重要集合
   * 记录 JdbcType 与 TypeHandler 之间的对应关系，其中 JdbcType 是一个枚举类型，它定义对应的 JDBC 类型
   * 该集合主要用于从结果集读取数据时，将数据从 Jdbc 类型转换成 Java 类型
   */
  private final Map<JdbcType, TypeHandler<?>> JDBC_TYPE_HANDLER_MAP = new EnumMap<>(JdbcType.class);
  /**
   * 2.重要集合
   * 记录了 Java类型向指定JdbcType 转换时，需要使用的 TypeHandler 对象。例如：Java类型中的String可能
   * 转换成数据库的 char、varchar 等多种类型，所以存在一对多关系
   * {@link TypeHandler} 的映射
   *
   * KEY1：Java Type
   * KEY2：JDBC Type
   * VALUE：{@link TypeHandler} 对象
   */
  private final Map<Type, Map<JdbcType, TypeHandler<?>>> TYPE_HANDLER_MAP = new ConcurrentHashMap<>();
  /**
   * {@link UnknownTypeHandler} 对象
   */
  private final TypeHandler<Object> UNKNOWN_TYPE_HANDLER = new UnknownTypeHandler(this);
  /**
   * 3.重要集合
   * 记录了全部 TypeHandler 的类型以及该类型相应的 TypeHandler 对象
   *
   * KEY：{@link TypeHandler#getClass()}
   * VALUE：{@link TypeHandler} 对象
   */
  private final Map<Class<?>, TypeHandler<?>> ALL_TYPE_HANDLERS_MAP = new HashMap<>();

  /**
   * 空 TypeHandler 集合的标识。即使 {@link #TYPE_HANDLER_MAP} 中，某个 KEY1 对应的 Map<JdbcType, TypeHandler<?>> 为空。
   */
  private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections.emptyMap();

  /**
   * 默认的枚举类型的 TypeHandler 对象
   */
  private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

  /**
   * 构造器
   * 将基础的 TypeHandler对象 注册到集合中
   */
  public TypeHandlerRegistry() {
    //1.boolean
    register(Boolean.class, new BooleanTypeHandler());
    register(boolean.class, new BooleanTypeHandler());
    register(JdbcType.BOOLEAN, new BooleanTypeHandler());
    register(JdbcType.BIT, new BooleanTypeHandler());

    //2.byte
    register(Byte.class, new ByteTypeHandler());
    register(byte.class, new ByteTypeHandler());
    register(JdbcType.TINYINT, new ByteTypeHandler());

    //3.short
    register(Short.class, new ShortTypeHandler());
    register(short.class, new ShortTypeHandler());
    register(JdbcType.SMALLINT, new ShortTypeHandler());

    //4.int
    register(Integer.class, new IntegerTypeHandler());
    register(int.class, new IntegerTypeHandler());
    register(JdbcType.INTEGER, new IntegerTypeHandler());

    //5.long
    register(Long.class, new LongTypeHandler());
    register(long.class, new LongTypeHandler());

    //6.float
    register(Float.class, new FloatTypeHandler());
    register(float.class, new FloatTypeHandler());
    register(JdbcType.FLOAT, new FloatTypeHandler());

    //7.double
    register(Double.class, new DoubleTypeHandler());
    register(double.class, new DoubleTypeHandler());
    register(JdbcType.DOUBLE, new DoubleTypeHandler());

    //9.String相关
    register(Reader.class, new ClobReaderTypeHandler());
    //StringTypeHandler能够将数据从 String 类型转换成 null (JdbcType)，所以向TYPE_HANDLER_MAP集合注册该对象，并向ALL_TYPE_HANDLERS_MAP注册该对象
    register(String.class, new StringTypeHandler());
    register(String.class, JdbcType.CHAR, new StringTypeHandler());
    register(String.class, JdbcType.CLOB, new ClobTypeHandler());
    register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
    register(String.class, JdbcType.LONGVARCHAR, new ClobTypeHandler());
    //NStringTypeHandler能够将数据从String类型转换成NVARCHAR，所以向TYPE_HANDLER_MAP集合注册该对象，并向ALL_TYPE_HANDLERS_MAP注册该对象
    register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
    register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
    register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
    register(JdbcType.CHAR, new StringTypeHandler());
    register(JdbcType.VARCHAR, new StringTypeHandler());
    register(JdbcType.CLOB, new ClobTypeHandler());
    register(JdbcType.LONGVARCHAR, new ClobTypeHandler());
    //向JDBC_TYPE_HANDLER_MAP集合注册NVARCHAR对应的NStringTypeHandler
    register(JdbcType.NVARCHAR, new NStringTypeHandler());
    register(JdbcType.NCHAR, new NStringTypeHandler());
    register(JdbcType.NCLOB, new NClobTypeHandler());

    //10.Object相关
    register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
    register(JdbcType.ARRAY, new ArrayTypeHandler());

    //11.BigInteger
    register(BigInteger.class, new BigIntegerTypeHandler());
    register(JdbcType.BIGINT, new LongTypeHandler());

    //12.BigDecimal
    register(BigDecimal.class, new BigDecimalTypeHandler());
    register(JdbcType.REAL, new BigDecimalTypeHandler());
    register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
    register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

    //13.Byte[]和InputStream
    register(InputStream.class, new BlobInputStreamTypeHandler());
    register(Byte[].class, new ByteObjectArrayTypeHandler());
    register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
    register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
    register(byte[].class, new ByteArrayTypeHandler());
    register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
    register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
    register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
    register(JdbcType.BLOB, new BlobTypeHandler());

    //14.Object相关
    register(Object.class, UNKNOWN_TYPE_HANDLER);
    register(Object.class, JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);
    register(JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);

    //15.Date相关
    register(Date.class, new DateTypeHandler());
    register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
    register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
    register(JdbcType.TIMESTAMP, new DateTypeHandler());
    register(JdbcType.DATE, new DateOnlyTypeHandler());
    register(JdbcType.TIME, new TimeOnlyTypeHandler());

    register(java.sql.Date.class, new SqlDateTypeHandler());
    register(java.sql.Time.class, new SqlTimeTypeHandler());
    register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

    register(String.class, JdbcType.SQLXML, new SqlxmlTypeHandler());

    register(Instant.class, InstantTypeHandler.class);
    register(LocalDateTime.class, LocalDateTimeTypeHandler.class);
    register(LocalDate.class, LocalDateTypeHandler.class);
    register(LocalTime.class, LocalTimeTypeHandler.class);
    register(OffsetDateTime.class, OffsetDateTimeTypeHandler.class);
    register(OffsetTime.class, OffsetTimeTypeHandler.class);
    register(ZonedDateTime.class, ZonedDateTimeTypeHandler.class);
    register(Month.class, MonthTypeHandler.class);
    register(Year.class, YearTypeHandler.class);
    register(YearMonth.class, YearMonthTypeHandler.class);
    register(JapaneseDate.class, JapaneseDateTypeHandler.class);

    //8.char
    // issue #273
    register(Character.class, new CharacterTypeHandler());
    register(char.class, new CharacterTypeHandler());
  }

  /**
   * Set a default {@link TypeHandler} class for {@link Enum}.
   * A default {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
   * @param typeHandler a type handler class for {@link Enum}
   * @since 3.4.5
   */
  public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
    this.defaultEnumTypeHandler = typeHandler;
  }

  //######################### hasTypeHandler方法（底层依赖getTypeHandler方法） ####################################################

  public boolean hasTypeHandler(Class<?> javaType) {
    return hasTypeHandler(javaType, null);
  }

  public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
    return hasTypeHandler(javaTypeReference, null);
  }

  public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
    return javaType != null && getTypeHandler((Type) javaType, jdbcType) != null;
  }

  public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
    return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
  }

  //#################################### getTypeHandler方法 ######################################################

  /**
   * 从ALL_TYPE_HANDLERS_MAP集合根据指定的handler类型获取TypeHandler对象
   * @param handlerType TypeHandler的类型
   * @return
   */
  public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
    return ALL_TYPE_HANDLERS_MAP.get(handlerType);
  }

  public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
    return getTypeHandler((Type) type, null);
  }

  public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
    return getTypeHandler(javaTypeReference, null);
  }

  /**
   * 从JDBC_TYPE_HANDLER_MAP集合中根据指定的JdbcType类型查找相应的TypeHandler对象
   * @param jdbcType 指定的JdbcType类型
   * @return
   */
  public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
    return JDBC_TYPE_HANDLER_MAP.get(jdbcType);
  }

  public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
    return getTypeHandler((Type) type, jdbcType);
  }

  public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
    return getTypeHandler(javaTypeReference.getRawType(), jdbcType);
  }

  /**
   * getTypeHandler底层方法实现[1]。根据指定的Java类型和JdbcType类型查找相应的TypeHandler对象
   * 0.type为ParamMap返回null
   * 1.优先查找指定type指定jdbcType的handler，
   * 2.未找到则查找指定type而jdbcType为null的handler，
   * 3.未找到则查找唯一的handler
   * 4.都未找到则返回null
   * @param type java类型
   * @param jdbcType jdbc类型
   * @param <T>
   * @return TypeHandler对象
   */
  @SuppressWarnings("unchecked")
  private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
    //0.
    if (ParamMap.class.equals(type)) {
      return null;
    }
    //1.查找Java类型对应的TypeHandler集合
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);
    TypeHandler<?> handler = null;
    if (jdbcHandlerMap != null) {
      //1.优先，使用 jdbcType 获取对应的 TypeHandler
      handler = jdbcHandlerMap.get(jdbcType);
      if (handler == null) {
        //2.其次，使用 null 获取对应的 TypeHandler ，可以认为是默认的 TypeHandler
        handler = jdbcHandlerMap.get(null);
      }
      if (handler == null) {
        // #591
        //3.最差，从 TypeHandler 集合中选择一个唯一的 TypeHandler
        handler = pickSoleHandler(jdbcHandlerMap);
      }
    }
    // type drives generics here
    return (TypeHandler<T>) handler;
  }

  /**
   * 从 TYPE_HANDLER_MAP 获取对应java类型的 TypeHandler 集合。
   * 由底层 {@link #getTypeHandler(Type, JdbcType)} 方法调用
   * @param type
   * @return
   */
  private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
    //1.获取指定Java类型的 TypeHandler 集合
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(type);
    //2.如果为 NULL_TYPE_HANDLER_MAP 空集合标识，则直接返回null
    if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
      return null;
    }
    //3.如果找不到该java类型的TypeHandler，则获取父类对应的TypeHandler集合
    if (jdbcHandlerMap == null && type instanceof Class) {
      Class<?> clazz = (Class<?>) type;
      //3.1枚举类型
      if (clazz.isEnum()) {
        //获得枚举接口对应的 TypeHandler 集合
        jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(clazz, clazz);
        //如果找不到，则返回枚举类的默认 defaultEnumTypeHandler（可以理解为 Enum类 对应的TypeHandler）
        if (jdbcHandlerMap == null) {
          //注册 defaultEnumTypeHandler，并使用它
          register(clazz, getInstance(clazz, defaultEnumTypeHandler));
          //返回结果
          return TYPE_HANDLER_MAP.get(clazz);
        }
      } else {
        //3.2非枚举类型，获得对应父类的 TypeHandler 集合
        jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
      }
    }
    //4.如果结果为空 或者 指定java类型为泛型，设置为 NULL_TYPE_HANDLER_MAP，提升查找速度，避免二次查找
    TYPE_HANDLER_MAP.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
    //返回结果
    return jdbcHandlerMap;
  }

  /**
   * 依据指定枚举类实现的枚举接口查找指定的 TypeHandler 集合
   * 由 {@link #getJdbcHandlerMap(Type)} 调用
   * @param clazz 指定类
   * @param enumClazz 枚举类
   * @return
   */
  private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz, Class<?> enumClazz) {
    //1.遍历枚举类的所有接口
    for (Class<?> iface : clazz.getInterfaces()) {
      //2.获得该接口对应的 TypeHandler 集合
      Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(iface);
      //3.集合为空，递归调用getJdbcHandlerMapForEnumInterfaces方法，继续从父实现类获取对应的TypeHandler集合
      if (jdbcHandlerMap == null) {
        jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
      }
      //4.找到集合，则从jdbcHandlerMap初始化到newMap中，并进行返回
      if (jdbcHandlerMap != null) {
        // Found a type handler regsiterd to a super interface
        HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<>();
        for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
          // Create a type handler instance with enum type as a constructor arg
          newMap.put(entry.getKey(), getInstance(enumClazz, entry.getValue().getClass()));
        }
        return newMap;
      }
    }
    // 找不到，则返回 null
    return null;
  }

  /**
   * 获得父类对应的 TypeHandler 集合
   * @param clazz 指定java类型
   * @return
   */
  private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
    //1.获得父类
    Class<?> superclass =  clazz.getSuperclass();
    //2.不存在非Object的父类，则返回null
    if (superclass == null || Object.class.equals(superclass)) {
      return null;
    }
    //3.获得父类对应的TypeHandler集合
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(superclass);
    //3.1找到，则直接返回
    if (jdbcHandlerMap != null) {
      return jdbcHandlerMap;
    } else {
      //3.2找不到，则递归调用getJdbcHandlerMapForSuperclass
      return getJdbcHandlerMapForSuperclass(superclass);
    }
  }

  private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
    TypeHandler<?> soleHandler = null;
    //1.遍历jdbcHandlerMap中的TypeHandler对象
    for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
      if (soleHandler == null) {
        soleHandler = handler;
      } else if (!handler.getClass().equals(soleHandler.getClass())) {
        //2.如果有多个handler，则返回null
        // More than one type handlers registered.
        return null;
      }
    }
    return soleHandler;
  }

  public TypeHandler<Object> getUnknownTypeHandler() {
    return UNKNOWN_TYPE_HANDLER;
  }

  //##################################### register方法 #########################################################

  /**
   * [5]
   * 通过 JdbcType 对象和 TypeHandler 对象注册
   * 向 JDBC_TYPE_HANDLER_MAP 集合注册对象
   * @param jdbcType jdbc类型
   * @param handler TypeHandler对象
   */
  public void register(JdbcType jdbcType, TypeHandler<?> handler) {
    //注册JDBC类型对应的TypeHandler
    JDBC_TYPE_HANDLER_MAP.put(jdbcType, handler);
  }

  //
  // REGISTER INSTANCE
  //

  // Only handler

  /**
   * [2]
   * 通过TypeHandler对象注册
   * 底层委托[3]注册
   * MappedTypes注解 优先于 TypeReference 父类，一旦有MappedTypes注解就不处理TypeReference 父类定义的java类型
   * @param typeHandler TypeHandler对象
   * @param <T>
   */
  @SuppressWarnings("unchecked")
  public <T> void register(TypeHandler<T> typeHandler) {
    boolean mappedTypeFound = false;
    //1.获取@MappedTypes注解，并根据注解指定的java类型进行注册。从注解中解析出java类型
    MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
    if (mappedTypes != null) {
      for (Class<?> handledType : mappedTypes.value()) {
        //交由重载的register[3]注册，即通过java类型和typeHandler对象注册
        register(handledType, typeHandler);
        mappedTypeFound = true;
      }
    }
    // @since 3.1.0 - try to auto-discover the mapped type
    // 从3.1.0版本开始，可以根据TypeHandler类型自动查找对应的Java类型，这需要 TypeHandler 实现类同时继承 TypeReference这个抽象类
    //2.获取 TypeReference 方法中提供的java类型来注册。从TypeReference中解析出java类型
    if (!mappedTypeFound && typeHandler instanceof TypeReference) {
      try {
        //强制转型TypeReference
        TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
        //交由重载的register[3]注册，即通过java类型和typeHandler对象注册
        register(typeReference.getRawType(), typeHandler);
        mappedTypeFound = true;
      } catch (Throwable t) {
        // maybe users define the TypeReference with a different type and are not assignable, so just ignore it
      }
    }
    //3.没有handler对应的java类型，就传入null交由重载的register[3]注册
    if (!mappedTypeFound) {
      register((Class<T>) null, typeHandler);
    }
  }

  // java type + handler

  public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
    register((Type) javaType, typeHandler);
  }

  /**
   * [3]
   * 通过TypeHandler对应的 javaType 和 typeHandler 对象注册
   * 底层通过[4]实现
   * @param javaType TypeHandler对应的java类型
   * @param typeHandler typeHandler 对象
   * @param <T>
   */
  private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
    //1.获取TypeHandler类的 @MappedJdbcTypes 注解
    MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);
    //2.有@MappedJdbcTypes注解。从注解中解析出JdbcType类型
    if (mappedJdbcTypes != null) {
      //2.1遍历注解value字段数组
      for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
        //委托register[4]处理
        register(javaType, handledJdbcType, typeHandler);
      }
      //2.2依据注解includeNullJdbcType字段值
      if (mappedJdbcTypes.includeNullJdbcType()) {
        //传入null委托register[4]处理
        register(javaType, null, typeHandler);
      }
    } else {
      //3.没有@MappedJdbcTypes注解。传入null的jdbcType给register[4]注册，即通过javaType和jdbcType和typeHandler对象注册
      register(javaType, null, typeHandler);
    }
  }

  public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
    register(javaTypeReference.getRawType(), handler);
  }

  // java type + jdbc type + handler

  public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
    register((Type) type, jdbcType, handler);
  }

  /**
   * [4]
   * 底层注册 TypeHandler 与类型关系的方法
   * 向 TYPE_HANDLER_MAP 集合和 ALL_TYPE_HANDLERS MAP 集合注册 TypeHandler 对象
   * @param javaType java类型
   * @param jdbcType jdbc类型
   * @param handler TypeHandler对象
   */
  private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
    //1.检测是否明确指明了 TypeHandler 能够处理的 Java类型
    if (javaType != null) {
      //1.1获取指定java类型在TYPE_HANDLER_MAP集合中对应的TypeHandler集合
      Map<JdbcType, TypeHandler<?>> map = TYPE_HANDLER_MAP.get(javaType);
      //1.2.如果TypeHandler集合为空，创建新的 TypeHandler集合，并添加到 TYPE_HANDLER_MAP 中
      if (map == null || map == NULL_TYPE_HANDLER_MAP) {
        map = new HashMap<>();
        TYPE_HANDLER_MAP.put(javaType, map);
      }
      //1.3.将 TypeHandler 对象注册到 TYPE_HANDLER_MAP 集合
      map.put(jdbcType, handler);
    }
    //5.向ALL_TYPE_HANDLERS_MAP集合注册 TypeHandler 类型和对应的 TypeHandler 对象
    ALL_TYPE_HANDLERS_MAP.put(handler.getClass(), handler);
  }

  //
  // REGISTER CLASS
  //

  // Only handler type

  /**
   * [1]
   * 通过 typeHandlerClass类型 注册
   * 底层委托[3]和[2]注册
   * @param typeHandlerClass
   */
  public void register(Class<?> typeHandlerClass) {
    boolean mappedTypeFound = false;
    //1.获取@MappedTypes注解
    MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
    if (mappedTypes != null) {
      //2.根据@MappedTypes注解中指定的Java类型进行注册
      for (Class<?> javaTypeClass : mappedTypes.value()) {
        //3.经过强制类型转换以及使用反射创建typeHandler对象之后，由重载的register[3]方法处理。即调用通过javal类型和typeHandler对象注册
        register(javaTypeClass, typeHandlerClass);
        mappedTypeFound = true;
      }
    }
    if (!mappedTypeFound) {
      //2.未指定@MappedTypes注解，交由重载的register[2]方法处理。即调用通过typeHandler对象注册
      register(getInstance(null, typeHandlerClass));
    }
  }

  // java type + handler type

  public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
    register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
  }

  public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
    register(javaTypeClass, getInstance(javaTypeClass, typeHandlerClass));
  }

  // java type + jdbc type + handler type

  public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
    register(javaTypeClass, jdbcType, getInstance(javaTypeClass, typeHandlerClass));
  }

  // Construct a handler (used also from Builders)

  /**
   * 根据java类型和typeHandler类型创建 TypeHandler对象
   * @param javaTypeClass TypeHandler对应的java类型
   * @param typeHandlerClass TypeHandler类型
   * @param <T>
   * @return
   */
  @SuppressWarnings("unchecked")
  public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
    if (javaTypeClass != null) {
      try {
        //1.获取构造器带参数Class反射对象
        Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
        //通过反射构造TypeHandler实例
        return (TypeHandler<T>) c.newInstance(javaTypeClass);
      } catch (NoSuchMethodException ignored) {
        // ignored
      } catch (Exception e) {
        throw new TypeException("Failed invoking constructor for handler " + typeHandlerClass, e);
      }
    }
    try {
      //2.获取空参的构造器反射对象
      Constructor<?> c = typeHandlerClass.getConstructor();
      //通过反射构造TypeHandler实例
      return (TypeHandler<T>) c.newInstance();
    } catch (Exception e) {
      throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass, e);
    }
  }

  // scan

  /**
   * [6]
   * 通过包名注册所有的TypeHandler对象
   * @param packageName 指定包名
   */
  public void register(String packageName) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    //1.查找指定包下TypeHandler接口实现类
    resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
    //获取类集合
    Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
    for (Class<?> type : handlerSet) {
      //Ignore inner classes and interfaces (including package-info.java) and abstract classes
      //过滤掉内部类、接口、以及抽象类
      if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
        //2.交由重载的register[1]进行注册
        register(type);
      }
    }
  }

  // get information

  /**
   * 返回注册的所有ALL_TYPE_HANDLERS_MAP集合中的TypeHandler
   * @since 3.2.2
   */
  public Collection<TypeHandler<?>> getTypeHandlers() {
    return Collections.unmodifiableCollection(ALL_TYPE_HANDLERS_MAP.values());
  }

}
