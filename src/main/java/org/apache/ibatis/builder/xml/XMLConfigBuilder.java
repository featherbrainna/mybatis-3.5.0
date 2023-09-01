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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * XML 配置构建器，主要负责解析 mybatis-config.xml 配置文件。
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  /**
   * 标识是否已经解析过mybatis-config.xml配置文件
   */
  private boolean parsed;
  /**
   * 用于解析mybatis-config.xml配置文件的 XPathParser 对象
   */
  private final XPathParser parser;
  /**
   * 标识<environment>配置的名称，默认读取<environment>标签的default属性
   */
  private String environment;
  /**
   * ReflectorFactory负责创建和缓存 Reflector 对象
   */
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  //#################################### 构造器 ########################################################

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  /**
   * 构造器，被 {@link SqlSessionFactoryBuilder#build(InputStream, String, Properties)} 调用解析xml文件
   * @param reader
   * @param environment
   * @param props
   */
  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    //根据xml文件输入流创建 XPathParser 对象，并调用底层构造器
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  /**
   * 构造器，被 {@link SqlSessionFactoryBuilder#build(InputStream, String, Properties)} 调用解析xml文件
   * @param inputStream
   * @param environment
   * @param props
   */
  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    //根据xml文件输入流创建 XPathParser 对象，并调用底层构造器
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  /**
   * 底层最终构造器
   * @param parser XPathParser对象
   * @param environment 环境
   * @param props Properties对象
   */
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    //1.创建 Configuration 对象，并初始化 BaseBuilder 父类属性
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    //2.设置 configuration 对象的 variables 属性
    this.configuration.setVariables(props);
    //3.初始化本类对象属性parsed、environment、parser
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 解析 XML 成 Configuration 对象，核心对外提供方法是解析 mybatis-config.xml 配置文件的入口
   * @return
   */
  public Configuration parse() {
    //1.若已解析，抛出 BuilderException 异常
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    //2.标记已解析
    parsed = true;
    //3.解析 XML configuration 节点。parser.evalNode("/configuration")通过xpath表达式获取 XNode 节点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * 底层解析xml节点。解析 <configuration> 节点
   * @param root 指定节点XNode对象
   */
  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      //1.解析<properties>标签
      propertiesElement(root.evalNode("properties"));
      //2.解析<settings>标签
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      //3.加载自定义 VFS、Log 实现类
      loadCustomVfs(settings);//设置 vfsimpl 属性
      loadCustomLogImpl(settings);//设置 LogImpl 属性
      //4.解析<typeAliases>标签
      typeAliasesElement(root.evalNode("typeAliases"));
      //5.解析<plugins>标签
      pluginElement(root.evalNode("plugins"));
      //6.解析<objectFactory>标签
      objectFactoryElement(root.evalNode("objectFactory"));
      //7.解析<objectWrapperFactory>标签
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      //8.解析<reflectorFactory>标签
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      //9.赋值<settings>到 Configuration 属性
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      //10.解析<environments>标签
      environmentsElement(root.evalNode("environments"));
      //11.解析<databaseIdProvider>标签
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      //12.解析<typeHandlers>标签
      typeHandlerElement(root.evalNode("typeHandlers"));
      //13.解析<mappers>标签
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 将 <setting> 标签解析为 Properties 对象
   * @param context 指定settings节点对象
   * @return Properties 对象
   */
  private Properties settingsAsProperties(XNode context) {
    //1.如果节点为null，则返回空的Properties对象
    if (context == null) {
      return new Properties();
    }
    //2.读取setting节点下的子标签为 Properties 对象
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    //3.创建 Configuration 对应的 MetaClass 对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    //4.检测 Configuration 中是否定义了 key 指定属性相应的 setter 方法，否则抛出异常
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  /**
   * 加载自定义 VFS 实现类
   * @param props 从 settings节点解析的 Properties 对象
   * @throws ClassNotFoundException
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    //1.获得 vfsImpl 属性
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      //2.拆分字符串为 clazzes 数组
      String[] clazzes = value.split(",");
      //3.遍历自定义的 VFS全类名 数组
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          //4.加载 VFS 类
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          //5.设置到 configuration 中，底层通过VFS.addImplClass(this.vfsImpl)设置到VFS类属性中
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  /**
   * 加载自定义 Log 实现类
   * @param props 从 settings节点解析的 Properties 对象
   */
  private void loadCustomLogImpl(Properties props) {
    //1.获取 Log 实现类的class对象
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    //2.设置到 configuration 中，底层通过LogFactory.useCustomLogging(this.logImpl)设置到LogFactory类属性中
    configuration.setLogImpl(logImpl);
  }

  /**
   * 解析 typeAliases 节点
   * @param parent typeAliases 节点
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      //1.遍历处理全部子节点
      for (XNode child : parent.getChildren()) {
        //2.如果子节点为package标签
        if ("package".equals(child.getName())) {
          //2.1获取package标签的name属性，即指定的包名
          String typeAliasPackage = child.getStringAttribute("name");
          //2.2通过 TypeAliasRegistry 对象扫描指定包中的所有类，并解析@Alias注解，完成别名注册（别名都是小写字母）
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          //2.如果子节点为typeAlias标签或其他标签
          //2.1获取 typeAlias 标签的 alias 属性，即指定的别名
          String alias = child.getStringAttribute("alias");
          //2.2获取 typeAlias 标签的 type 属性，即别名对应的类型
          String type = child.getStringAttribute("type");
          try {
            //2.3加载类型对应的class对象
            Class<?> clazz = Resources.classForName(type);
            //2.4使用 typeAliasRegistry 对象注册到 typeAliasRegistry 中
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析 plugins 节点
   * @param parent plugins 节点
   * @throws Exception 异常
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      //1.遍历plugins节点的全部子节点，即 plugin 节点
      for (XNode child : parent.getChildren()) {
        //2.获取 plugin 节点的 interceptor 属性值
        String interceptor = child.getStringAttribute("interceptor");
        //3.获取 plugin 节点下 properties 配置的信息，并形成 properties 对象
        Properties properties = child.getChildrenAsProperties();
        //4.创建 Interceptor 对象并设置属性
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        //5.将 Interceptor 对象添加到 configuration 对象中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 解析 objectFactory 节点
   * @param context objectFactory 节点
   * @throws Exception 异常
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      //1.获取objectFactory节点 type 属性
      String type = context.getStringAttribute("type");
      //2.获取objectFactory节点下的配置信息，并形成 Properties 对象
      Properties properties = context.getChildrenAsProperties();
      //3.依据 type 创建自定义 ObjectFactory 对象
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      //4.设置 properties 属性
      factory.setProperties(properties);
      //5.将 ObjectFactory 对象设置到configuration中
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 解析 objectWrapperFactory 节点
   * @param context objectWrapperFactory 节点
   * @throws Exception
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      //1.读取 objectWrapperFactory 节点的 type 属性
      String type = context.getStringAttribute("type");
      //2.依据 type 创建 ObjectWrapperFactory 对象
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      //3.将 ObjectWrapperFactory 对象设置到 configuration 中
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 解析 reflectorFactory 节点
   * @param context reflectorFactory节点
   * @throws Exception
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      //1.读取 reflectorFactory 节点的 type 属性
      String type = context.getStringAttribute("type");
      //2.依据 type 创建 ReflectorFactory 对象
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
      //3.将 ReflectorFactory 对象设置到 configuration 中
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 解析 properties 节点
   * 1.通过方法参数传递的属性具有最高优先级，
   * 2.resource/url 属性中指定的配置文件次之，
   * 3.最低优先级的则是 properties 元素中指定的属性。
   * @param context
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      //1.读取properties节点下的子标签为 Properties 对象
      Properties defaults = context.getChildrenAsProperties();
      //2.读取 properties 节点的resource和url属性
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      //3.如果properties节点同时存在resource和url属性，抛出异常
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      //3.读取本地 properties 配置文件到 defaults 中
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        //3.读取远程 properties 配置文件到 defaults 中
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      //4.读取 configuration 中的 variables 属性 到 defaults 中
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      //5.设置 parser 和 configuration 字段
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  /**
   * 赋值<settings>读取的 Properties 对象到 Configuration 属性。
   * @param props
   */
  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 解析 environments 节点
   * @param context environments 节点
   * @throws Exception 异常
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      //1.未指定 environment 属性，则使用 environments 节点的default属性赋值给environment属性
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      //2.遍历 environment 子节点
      for (XNode child : context.getChildren()) {
        //3.获取 environment 子节点的id属性
        String id = child.getStringAttribute("id");
        //4.当前节点的id属性是否与 environment 属性匹配
        if (isSpecifiedEnvironment(id)) {
          //4.1解析 environment 子节点的子节点 transactionManager，并返回 TransactionFactory 对象
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          //4.2解析 environment 子节点的子节点 dataSource，并返回 DataSourceFactory 对象
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          //4.3通过 DataSourceFactory 对象创建 DataSource 对象
          DataSource dataSource = dsFactory.getDataSource();
          //4.4创建 Environment.Builder 对象，并将 id、TransactionFactory、DataSource 设置到 Environment.Builder 对象
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          //4.5将 Environment 对象设置到 configuration 对象中
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 解析 databaseIdProvider 节点
   * @param context databaseIdProvider 节点
   * @throws Exception 异常
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      //1.获取 databaseIdProvider 节点的 type 属性
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      //type属性可以简写为 VENDOR 在这里会转换为 DB_VENDOR
      if ("VENDOR".equals(type)) {
          type = "DB_VENDOR";
      }
      //2.获取 databaseIdProvider 节点的子节点为 Properties 对象
      Properties properties = context.getChildrenAsProperties();
      //3.根据 type 解析为 VendorDatabaseIdProvider 对象，并设置属性
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    //4.从 configuration 中获取 Environment 对象
    Environment environment = configuration.getEnvironment();
    //5.如果 environment 和 databaseIdProvider 不为 null
    if (environment != null && databaseIdProvider != null) {
      //5.1根据 databaseIdProvider 和 environment.getDataSource() 获取 databaseId
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      //5.2将 databaseId 设置到 configuration 中
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 解析 transactionManager 节点，返回 TransactionFactory 对象
   * @param context transactionManager 节点
   * @return TransactionFactory 对象
   * @throws Exception 异常
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      //1.获取 transactionManager 节点的 type属性
      String type = context.getStringAttribute("type");
      //2.获取 transactionManager 节点的子节点为 Properties 对象
      Properties props = context.getChildrenAsProperties();
      //3.依据 type 创建 TransactionFactory 对象并设置属性
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      //4.返回 TransactionFactory 对象
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  /**
   * 解析 dataSource 节点，返回 DataSourceFactory 对象
   * @param context dataSource 节点
   * @return  DataSourceFactory 对象
   * @throws Exception 异常
   */
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      //1.获取 dataSource 节点的 type 属性
      String type = context.getStringAttribute("type");
      //2.获取 dataSource 节点的子节点为 Properties 对象
      Properties props = context.getChildrenAsProperties();
      //3.依据 type 创建 DataSourceFactory 对象，并设置属性
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      //4.返回 DataSourceFactory 对象
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 解析 typeHandlers 节点
   * @param parent typeHandlers 节点
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      //1.遍历 typeHandlers 节点下的子节点 typeHandler 节点和 package 节点
      for (XNode child : parent.getChildren()) {
        //2.如果子节点名为 package
        if ("package".equals(child.getName())) {
          //2.1获取节点的name属性值，即包名
          String typeHandlerPackage = child.getStringAttribute("name");
          //2.2将包中所有 typeHandler 注册到 typeHandlerRegistry
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          //2.如果子节点名不为 package，则为 typeHandler 节点
          //2.1通过节点属性获取 javaType、jdbcType、typeHandler
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          //2.2注册 typeHandlerClass 到 typeHandlerRegistry 中
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析 mappers 节点
   * @param parent mappers 节点
   * @throws Exception 异常
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      //1.遍历 mappers 节点的子节点 mapper 节点或 package 节点
      for (XNode child : parent.getChildren()) {
        //2.如果子节点名为 package
        if ("package".equals(child.getName())) {
          //2.1获取 mapper 接口包名
          String mapperPackage = child.getStringAttribute("name");
          //2.2将 mapper 接口的代理class添加到 mapperRegistry
          configuration.addMappers(mapperPackage);
        } else {
          //2.如果子节点不为 package 即为 mapper
          //2.1从节点属性中获取 resource、url、class 属性
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          //2.2如果节点只指定了 resource 属性，即相对类路径的 mapper.xml 资源
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            //2.2.1读取 resource 的 InputStream
            InputStream inputStream = Resources.getResourceAsStream(resource);
            //2.2.2使用 InputStream 创建 XMLMapperBuilder 对象
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            //2.2.3解析 mapper.xml 文件和 mapper 接口
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            //2.3如果节点只指定了 url 属性，完全限定资源定位符
            ErrorContext.instance().resource(url);
            //2.3.1读取 url 的 InputStream
            InputStream inputStream = Resources.getUrlAsStream(url);
            //2.3.2使用 InputStream 创建 XMLMapperBuilder 对象
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            //2.3.3解析 mapper.xml 文件和 mapper 接口
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            //2.4如果节点只指定了 class 属性，mapper接口
            //读取 mapper 接口为 class
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            //解析mapper接口和xml文件添加到 configuration
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
