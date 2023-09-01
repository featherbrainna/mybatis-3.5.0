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
package org.apache.ibatis.builder.annotation;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * Mapper接口 注解构造器，负责解析 Mapper 接口上的注解。
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

  /**
   * SQL 操作注解集合
   */
  private static final Set<Class<? extends Annotation>> SQL_ANNOTATION_TYPES = new HashSet<>();
  /**
   * SQL 操作提供者注解集合
   */
  private static final Set<Class<? extends Annotation>> SQL_PROVIDER_ANNOTATION_TYPES = new HashSet<>();

  /**
   * Configuration 对象
   */
  private final Configuration configuration;
  /**
   * MapperBuilderAssistant构建助手
   */
  private final MapperBuilderAssistant assistant;
  /**
   * Mapper 接口类
   */
  private final Class<?> type;

  /**
   * 加载类时初始化两个集合 SQL_ANNOTATION_TYPES 和 SQL_PROVIDER_ANNOTATION_TYPES
   */
  static {
    SQL_ANNOTATION_TYPES.add(Select.class);
    SQL_ANNOTATION_TYPES.add(Insert.class);
    SQL_ANNOTATION_TYPES.add(Update.class);
    SQL_ANNOTATION_TYPES.add(Delete.class);

    SQL_PROVIDER_ANNOTATION_TYPES.add(SelectProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(InsertProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(UpdateProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(DeleteProvider.class);
  }

  /**
   * 构造器，由 {@link org.apache.ibatis.binding.MapperRegistry#addMapper(Class)} 调用
   * @param configuration
   * @param type
   */
  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    //替换
    String resource = type.getName().replace('.', '/') + ".java (best guess)";
    //创建 MapperBuilderAssistant 构建助手对象
    this.assistant = new MapperBuilderAssistant(configuration, resource);
    this.configuration = configuration;
    this.type = type;
  }

  /**
   * 解析 Mapper 接口中的注解
   */
  public void parse() {
    //1.获取Mapper接口全类名
    String resource = type.toString();
    //2.检测是否已经加载过该 Mapper 接口
    if (!configuration.isResourceLoaded(resource)) {
      //2.1检测是否加载过对应的映射配置文件，如果未加载，则创建 XMLMapperBuilder 对象解析对应文件
      loadXmlResource();
      //2.2configuration中标记已解析Mapper接口
      configuration.addLoadedResource(resource);
      //2.3assistant设置命名空间
      assistant.setCurrentNamespace(type.getName());
      //2.4解析 @CacheNamespace 注解
      parseCache();
      //2.5解析 @CacheNamespaceRef 注解
      parseCacheRef();
      //2.6获取接口中的全部方法
      Method[] methods = type.getMethods();
      //2.7遍历反射方法对象
      for (Method method : methods) {
        try {
          // issue #237
          if (!method.isBridge()) {
            //2.8解析方法上的注解，并创建 MappedStatement 对象
            parseStatement(method);
          }
        } catch (IncompleteElementException e) {
          //2.8解析接口方法注解失败，可能是引用了未解析的注解，添加到 configuration.incompleteMethod 集合暂存
          configuration.addIncompleteMethod(new MethodResolver(this, method));
        }
      }
    }
    //2.9解析未完成的接口方法
    parsePendingMethods();
  }

  private void parsePendingMethods() {
    Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
    synchronized (incompleteMethods) {
      Iterator<MethodResolver> iter = incompleteMethods.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // This method is still missing a resource
        }
      }
    }
  }

  /**
   * 加载 Mapper 接口对应的 mapper.xml 文件
   */
  private void loadXmlResource() {
    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace
    //判断 mapper.xml 文件是否已经加载过（使用namespace:全类名检查），如果加载过就不加载了
    if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
      //1.获取 mapper 接口对应的 mapper.xml 文件相对类路径地址
      String xmlResource = type.getName().replace('.', '/') + ".xml";
      // #1347
      //2.获取 mapper.xml 文件的 InputStream 输入流对象，通过Class#getResourceAsStream(String name)实现
      InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
      //3.未获取到对应的inputStream，再通过类加载器获取 Resources.getResourceAsStream(...)
      if (inputStream == null) {
        // Search XML mapper that is not in the module but in the classpath.
        try {
          inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
        } catch (IOException e2) {
          // ignore, resource is not required
        }
      }
      //4.通过创建 XMLMapperBuilder 对象，解析 inputStream 的 mapper.xml 文件
      if (inputStream != null) {
        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
        xmlParser.parse();
      }
    }
  }

  /**
   * 解析 @CacheNamespace 注解，类似 mapper.xml 中 cache 节点的作用
   */
  private void parseCache() {
    //1.获得类上的 @CacheNamespace 注解
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    //2.获取的注解不为空时，解析注解
    if (cacheDomain != null) {
      //2.1获取注解设置的 缓存大小、清空间隔 属性
      Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
      Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
      //2.2获取注解设置的 Properties 对象
      Properties props = convertToProperties(cacheDomain.properties());
      //2.3通过注解内容创建 Cache 对象，并通过assistant添加到 configuration
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
    }
  }

  /**
   * 属性注解数组 转换成 Properties 对象
   * @param properties
   * @return
   */
  private Properties convertToProperties(Property[] properties) {
    if (properties.length == 0) {
      return null;
    }
    Properties props = new Properties();
    for (Property property : properties) {
      props.setProperty(property.name(),
          PropertyParser.parse(property.value(), configuration.getVariables()));
    }
    return props;
  }

  /**
   * 解析 @CacheNamespaceRef 注解，类似 mapper.xml 中 cacheRef 节点的作用
   */
  private void parseCacheRef() {
    //1.获得类上的 @CacheNamespaceRef 注解
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    //2.若 cacheDomainRef 注解对象不为空，解析注解
    if (cacheDomainRef != null) {
      //3.获取注解属性 value、name
      Class<?> refType = cacheDomainRef.value();
      String refName = cacheDomainRef.name();
      //4.value、name属性不能同时为空
      if (refType == void.class && refName.isEmpty()) {
        throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
      }
      //5.value、name属性不能同时不为空
      if (refType != void.class && !refName.isEmpty()) {
        throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
      }
      //6.解析 value、name属性 中的其中一个为 namespace 字符串
      String namespace = (refType != void.class) ? refType.getName() : refName;
      try {
        //7.获取指向的 Cache 对象
        assistant.useCacheRef(namespace);
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(new CacheRefResolver(assistant, namespace));
      }
    }
  }

  /**
   * 如果没有 @ResultMap 注解则解析其它注解，返回 resultMapId 属性
   * @param method method反射对象
   * @return resultMapId字符串
   */
  private String parseResultMap(Method method) {
    //1.获取方法返回类型
    Class<?> returnType = getReturnType(method);
    ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
    Results results = method.getAnnotation(Results.class);
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    String resultMapId = generateResultMapName(method);
    applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
    return resultMapId;
  }

  private String generateResultMapName(Method method) {
    Results results = method.getAnnotation(Results.class);
    if (results != null && !results.id().isEmpty()) {
      return type.getName() + "." + results.id();
    }
    StringBuilder suffix = new StringBuilder();
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    return type.getName() + "." + method.getName() + suffix;
  }

  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
    List<ResultMapping> resultMappings = new ArrayList<>();
    applyConstructorArgs(args, returnType, resultMappings);
    applyResults(results, returnType, resultMappings);
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    // TODO add AutoMappingBehaviour
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      for (Case c : discriminator.cases()) {
        String caseResultMapId = resultMapId + "-" + c.value();
        List<ResultMapping> resultMappings = new ArrayList<>();
        // issue #136
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
        applyResults(c.results(), resultType, resultMappings);
        // TODO add AutoMappingBehaviour
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
      }
    }
  }

  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      String column = discriminator.column();
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<>();
      for (Case c : cases) {
        String value = c.value();
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    return null;
  }

  /**
   * 解析方法上的 SQL 操作相关的注解。类似 mapper.xml 中 SELECT|INSERT|UPDATE|DELETE 节点的作用
   * 解析与 sql 语句相关的注解，包括：@Lang\@Select...\@SelectProvider...\@Options\@SelectKey\@ResultMap
   * @param method
   */
  void parseStatement(Method method) {
    //1.依据 method 反射对象获取参数的类型（大多数情况为ParamMap.class）
    Class<?> parameterTypeClass = getParameterType(method);
    //2.依据 method 反射对象获取 LanguageDriver 对象（Lang注解解析）
    LanguageDriver languageDriver = getLanguageDriver(method);
    //3.依据 method 反射对象获取 SqlSource 对象（Select\Insert\Update\Delete和XXXprovider注解解析）
    SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);
    //4.如果 sqlSource 不为空，则进行解析处理
    if (sqlSource != null) {
      //4.1获取sql语句的各种属性（options注解中设置，Select\Insert\Update\Delete注解解析）
      Options options = method.getAnnotation(Options.class);
      final String mappedStatementId = type.getName() + "." + method.getName();
      Integer fetchSize = null;
      Integer timeout = null;
      StatementType statementType = StatementType.PREPARED;
      ResultSetType resultSetType = null;
      //依据 method 获取 sql 语句类型，UNKNOWN, INSERT, UPDATE, DELETE, SELECT, FLUSH
      SqlCommandType sqlCommandType = getSqlCommandType(method);
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
      boolean flushCache = !isSelect;//非select语句直接清空缓存
      boolean useCache = isSelect;//select语句使用缓存

      //4.2获取sql语句的 KeyGenerator 对象（SelectKey注解解析）
      KeyGenerator keyGenerator;
      String keyProperty = null;
      String keyColumn = null;
      //4.2如果 sql 语句类型为 INSERT 或 UPDATE
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        // first check for SelectKey annotation - that overrides everything else
        //4.2.1获取方法上的 SelectKey 注解对象
        SelectKey selectKey = method.getAnnotation(SelectKey.class);
        //4.2.2如果 selectKey 注解对象不为空
        if (selectKey != null) {
          //从 selectKey 注解解析出 keyGenerator 对象
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
          keyProperty = selectKey.keyProperty();
        } else if (options == null) {
          //4.2.2如果 selectKey 注解对象为空且 options 注解对象为空，
          // 则依据 mybatis-config.xml 的 settings 节点配置的useGeneratedKeys来获取keyGenerator对象
          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        } else {
          //4.2.2如果 selectKey 注解对象为空且 options 注解对象不为空，
          // 则依据 options 注解对象中属性获取 keyGenerator 对象
          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
          keyProperty = options.keyProperty();
          keyColumn = options.keyColumn();
        }
      } else {
        //4.2如果 sql 语句为 select、delete 则 keyGenerator 为 NoKeyGenerator.INSTANCE
        keyGenerator = NoKeyGenerator.INSTANCE;
      }

      //4.3使用 options 注解初始化sql语句属性（Options注解解析）
      if (options != null) {
        if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
          flushCache = true;
        } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
          flushCache = false;
        }
        useCache = options.useCache();
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        resultSetType = options.resultSetType();
      }

      //4.4获取 resultMapId 编号字符串（ResultMap注解解析）
      String resultMapId = null;
      //4.4.1获取方法上的 ResultMap 注解对象
      ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
      //4.4.2如果 resultMapAnnotation 注解对象不为空
      if (resultMapAnnotation != null) {
        //4.2.2.1获取注解中指向的 mapper.xml 中定义的 resultMap 的编号数组
        String[] resultMaps = resultMapAnnotation.value();
        //4.2.2.2遍历 resultMaps 数组
        StringBuilder sb = new StringBuilder();
        for (String resultMap : resultMaps) {
          if (sb.length() > 0) {
            sb.append(",");
          }
          //拼接多个 resultMap 的编号为字符串
          sb.append(resultMap);
        }
        resultMapId = sb.toString();
      } else if (isSelect) {
        //4.4.2如果 resultMapAnnotation 注解对象为空，解析其它注解，作为 resultMapId 属性
        resultMapId = parseResultMap(method);
      }

      //4.5构建 MappedStatement 对象
      assistant.addMappedStatement(
          mappedStatementId,
          sqlSource,
          statementType,
          sqlCommandType,
          fetchSize,
          timeout,
          // ParameterMapID
          null,
          parameterTypeClass,
          resultMapId,
          getReturnType(method),//获得方法返回类型
          resultSetType,
          flushCache,
          useCache,
          // TODO gcode issue #577
          false,
          keyGenerator,
          keyProperty,
          keyColumn,
          // DatabaseID
          null,
          languageDriver,
          // ResultSets
          options != null ? nullOrEmpty(options.resultSets()) : null);
    }
  }

  /**
   * 依据 method 反射对象获取 LanguageDriver 对象
   * @param method
   * @return
   */
  private LanguageDriver getLanguageDriver(Method method) {
    //1.从方法上获取 Lang 注解对象
    Lang lang = method.getAnnotation(Lang.class);
    Class<? extends LanguageDriver> langClass = null;
    //2.如果 lang 注解对象不为 null，则获取value属性赋值给 langClass
    if (lang != null) {
      langClass = lang.value();
    }
    //3.基于 assistant 和 langClass 获取 LanguageDriver 对象
    return assistant.getLanguageDriver(langClass);
  }

  /**
   * 依据 method 反射对象获取参数的类型
   * 排除 RowBounds 和 ResultHandler 两种参数
   * 1. 如果是多参数，则是 ParamMap 类型
   * 2. 如果是单参数，则是该参数的类型
   * @param method
   * @return
   */
  private Class<?> getParameterType(Method method) {
    //参数类型
    Class<?> parameterType = null;
    //1.遍历所有的方法参数类型
    Class<?>[] parameterTypes = method.getParameterTypes();
    for (Class<?> currentParameterType : parameterTypes) {
      //2.如果参数类型不是 RowBounds 和 ResultHandler
      if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
        //2.1如果 parameterType 为空，则直接 parameterType 赋值为当前方法参数类型
        if (parameterType == null) {
          parameterType = currentParameterType;
        } else {
          // issue #135
          //2.2如果 parameterType 不为空，则 parameterType 赋值为 ParamMap
          parameterType = ParamMap.class;
        }
      }
    }
    return parameterType;
  }

  /**
   * 获得方法返回类型
   * @param method
   * @return
   */
  private Class<?> getReturnType(Method method) {
    Class<?> returnType = method.getReturnType();
    Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
    if (resolvedReturnType instanceof Class) {
      returnType = (Class<?>) resolvedReturnType;
      if (returnType.isArray()) {
        returnType = returnType.getComponentType();
      }
      // gcode issue #508
      if (void.class.equals(returnType)) {
        ResultType rt = method.getAnnotation(ResultType.class);
        if (rt != null) {
          returnType = rt.value();
        }
      }
    } else if (resolvedReturnType instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
      Class<?> rawType = (Class<?>) parameterizedType.getRawType();
      if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          Type returnTypeParameter = actualTypeArguments[0];
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue #443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          } else if (returnTypeParameter instanceof GenericArrayType) {
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            // (gcode issue #525) support List<byte[]>
            returnType = Array.newInstance(componentType, 0).getClass();
          }
        }
      } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
        // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
          if (actualTypeArguments != null && actualTypeArguments.length == 2) {
            Type returnTypeParameter = actualTypeArguments[1];
            if (returnTypeParameter instanceof Class<?>) {
              returnType = (Class<?>) returnTypeParameter;
            } else if (returnTypeParameter instanceof ParameterizedType) {
              // (gcode issue 443) actual type can be a also a parameterized type
              returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
            }
          }
      } else if (Optional.class.equals(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        Type returnTypeParameter = actualTypeArguments[0];
        if (returnTypeParameter instanceof Class<?>) {
          returnType = (Class<?>) returnTypeParameter;
        }
      }
    }

    return returnType;
  }

  /**
   * 从注解中，获得 SqlSource 对象
   * @param method 方法反射对象
   * @param parameterType 参数类型
   * @param languageDriver LanguageDriver对象
   * @return SqlSource对象
   */
  private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
    try {
      //1.获得方法上的 SQL_ANNOTATION_TYPES 注解类型
      Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
      //2.获得方法上的 SQL_PROVIDER_ANNOTATION_TYPES 注解类型
      Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
      //3.如果 SQL_ANNOTATION_TYPES 对应的类型非空
      if (sqlAnnotationType != null) {
        //3.1如果同时有 SQL_PROVIDER_ANNOTATION_TYPES 对应的类型，即同时方法上有注解 @Select 和 @SelectProvider就抛出异常
        if (sqlProviderAnnotationType != null) {
          throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
        }
        //3.2从方法上获取 sqlAnnotationType 对应的注解对象
        Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
        //3.3通过反射获取 Annotation 对象的 value 属性（由于 Annotation 没有value属性且无法强制转型只能通过反射获取属性）
        final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
        //3.4创建 SqlSource 对象
        return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
      } else if (sqlProviderAnnotationType != null) {
        //3.如果 SQL_PROVIDER_ANNOTATION_TYPES 对应的类型非空
        //3.1从方法上获取 sqlProviderAnnotationType 对应的注解对象
        Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
        //3.2创建 ProviderSqlSource 对象
        return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation, type, method);
      }
      //4.没有符合类型的注解，返回null
      return null;
    } catch (Exception e) {
      throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
    }
  }

  /**
   * 从解析注解的 String[] 中创建 SqlSource 对象
   * @param strings String数组
   * @param parameterTypeClass 参数类型
   * @param languageDriver LanguageDriver对象
   * @return SqlSource对象
   */
  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    //1.使用 " " 拼接 SQL 语句
    final StringBuilder sql = new StringBuilder();
    for (String fragment : strings) {
      sql.append(fragment);
      sql.append(" ");
    }
    //2.依赖 languageDriver 对象创建 SqlSource 对象
    return languageDriver.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
  }

  /**
   * 获取 sql 语句注解的 SqlCommandType 类型，获得方法对应的 SQL 命令类型
   * @param method
   * @return
   */
  private SqlCommandType getSqlCommandType(Method method) {
    //1.获取方法 method 的 SQL_ANNOTATION_TYPES 类型的注解类型
    Class<? extends Annotation> type = getSqlAnnotationType(method);

    //2.如果类型为空
    if (type == null) {
      //2.1获取 SQL_PROVIDER_ANNOTATION_TYPES 类型的注解类型
      type = getSqlProviderAnnotationType(method);

      //2.2如果类型为空，返回 SqlCommandType.UNKNOWN
      if (type == null) {
        return SqlCommandType.UNKNOWN;
      }

      //2.3如果类型为 SelectProvider ，则赋值 type 为 Select
      if (type == SelectProvider.class) {
        type = Select.class;
      } else if (type == InsertProvider.class) {
        type = Insert.class;
      } else if (type == UpdateProvider.class) {
        type = Update.class;
      } else if (type == DeleteProvider.class) {
        type = Delete.class;
      }
    }

    //3.返回类型的枚举类型，转换成对应的枚举
    return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
  }

  /**
   * 获得方法上的 SQL_ANNOTATION_TYPES 类型的注解类型
   * @param method
   * @return
   */
  private Class<? extends Annotation> getSqlAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_ANNOTATION_TYPES);
  }

  /**
   * 获得方法上的 SQL_PROVIDER_ANNOTATION_TYPES 类型的注解类型
   * @param method
   * @return
   */
  private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_PROVIDER_ANNOTATION_TYPES);
  }

  /**
   * 获取方法上指定 注解类型集合中的 注解类型
   * @param method 指定方法
   * @param types 指定注解类型集合
   * @return 注解类型
   */
  private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
    //1.遍历注解类型集合
    for (Class<? extends Annotation> type : types) {
      //2.从方法上获取注解类型的对象
      Annotation annotation = method.getAnnotation(type);
      //3.注解对象不为空，返回注解类型Class对象
      if (annotation != null) {
        return type;
      }
    }
    return null;
  }

  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Result result : results) {
      List<ResultFlag> flags = new ArrayList<>();
      if (result.id()) {
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(result.property()),
          nullOrEmpty(result.column()),
          result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          null,
          null,
          null,
          typeHandler,
          flags,
          null,
          null,
          isLazy(result));
      resultMappings.add(resultMapping);
    }
  }

  private String nestedSelectId(Result result) {
    String nestedSelect = result.one().select();
    if (nestedSelect.length() < 1) {
      nestedSelect = result.many().select();
    }
    if (!nestedSelect.contains(".")) {
      nestedSelect = type.getName() + "." + nestedSelect;
    }
    return nestedSelect;
  }

  private boolean isLazy(Result result) {
    boolean isLazy = configuration.isLazyLoadingEnabled();
    if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = result.one().fetchType() == FetchType.LAZY;
    } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = result.many().fetchType() == FetchType.LAZY;
    }
    return isLazy;
  }

  private boolean hasNestedSelect(Result result) {
    if (result.one().select().length() > 0 && result.many().select().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return result.one().select().length() > 0 || result.many().select().length() > 0;
  }

  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Arg arg : args) {
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if (arg.id()) {
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(arg.name()),
          nullOrEmpty(arg.column()),
          arg.javaType() == void.class ? null : arg.javaType(),
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
          nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()),
          null,
          nullOrEmpty(arg.columnPrefix()),
          typeHandler,
          flags,
          null,
          null,
          false);
      resultMappings.add(resultMapping);
    }
  }

  private String nullOrEmpty(String value) {
    return value == null || value.trim().length() == 0 ? null : value;
  }

  private Result[] resultsIf(Results results) {
    return results == null ? new Result[0] : results.value();
  }

  private Arg[] argsIf(ConstructorArgs args) {
    return args == null ? new Arg[0] : args.value();
  }

  /**
   * 处理 @SelectKey 注解，生成对应的 SelectKey 对象
   * @param selectKeyAnnotation SelectKey注解对象
   * @param baseStatementId
   * @param parameterTypeClass
   * @param languageDriver
   * @return
   */
  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    //1.获取 SelectKey 注解对象中设置的属性
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    // defaults
    //2.创建 MappedStatement 需要用到的默认属性
    boolean useCache = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    //3.依据 String 数组创建 SqlSource 对象
    SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    //4.创建 MappedStatement 对象并添加到 configuration
    assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
        flushCache, useCache, false,
        keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

    //5.获取完整的 id
    id = assistant.applyCurrentNamespace(id, false);

    //6.从 configuration 获取 MappedStatement 对象
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    //7.创建 SelectKeyGenerator 对象，并添加到 configuration 中
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

}
