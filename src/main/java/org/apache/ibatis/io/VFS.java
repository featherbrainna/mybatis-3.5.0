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
package org.apache.ibatis.io;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 虚拟文件系统抽象类，用来查找指定路径下的的文件们。
 * Provides a very simple API for accessing resources within an application server.
 *
 * @author Ben Gunter
 */
public abstract class VFS {
  private static final Log log = LogFactory.getLog(VFS.class);

  /**
   * 记录了MyBatis提供的两个VFS实现类
   * The built-in implementations.
   */
  public static final Class<?>[] IMPLEMENTATIONS = { JBoss6VFS.class, DefaultVFS.class };

  /**
   * 记录了用户自定义的VFS实现。
   * addImplClass(Class)方法会将指定的VFS用户自定义实现对应的class对象添加到 USER_IMPLEMENTATIONS 集合
   * The list to which implementations are added by {@link #addImplClass(Class)}.
   */
  public static final List<Class<? extends VFS>> USER_IMPLEMENTATIONS = new ArrayList<>();

  /**
   * 私有静态内部类写法的单例模式-懒汉式
   * Singleton instance holder.
   */
  private static class VFSHolder {
    //包可见类字段记录单例实例，类加载时调用createVFS()方法创建单例实例
    static final VFS INSTANCE = createVFS();

    /**
     * 创建单例对象
     * @return
     */
    @SuppressWarnings("unchecked")
    static VFS createVFS() {
      // Try the user implementations first, then the built-ins
      //1.获取所有 VFS类型对象 集合
      List<Class<? extends VFS>> impls = new ArrayList<>();
      impls.addAll(USER_IMPLEMENTATIONS);//先添加用户定义的VFS
      impls.addAll(Arrays.asList((Class<? extends VFS>[]) IMPLEMENTATIONS));//后添加内置的

      // Try each implementation class until a valid one is found
      VFS vfs = null;
      //2.遍历所有VFS类型对象，直到vfs！=null并且vfs合法
      for (int i = 0; vfs == null || !vfs.isValid(); i++) {
        //3.依次获取集合中VFS类型对象
        Class<? extends VFS> impl = impls.get(i);
        try {
          //4.通过反射构造vfs对象
          vfs = impl.newInstance();
          //5.vfs对象不合法记录日志
          if (vfs == null || !vfs.isValid()) {
            if (log.isDebugEnabled()) {
              log.debug("VFS implementation " + impl.getName() +
                  " is not valid in this environment.");
            }
          }
        } catch (InstantiationException e) {
          log.error("Failed to instantiate " + impl, e);
          return null;
        } catch (IllegalAccessException e) {
          log.error("Failed to instantiate " + impl, e);
          return null;
        }
      }

      if (log.isDebugEnabled()) {
        log.debug("Using VFS adapter " + vfs.getClass().getName());
      }

      return vfs;
    }
  }

  /**
   * 通过内部类获取懒加载单例对象
   * Get the singleton {@link VFS} instance. If no {@link VFS} implementation can be found for the
   * current environment, then this method returns null.
   */
  public static VFS getInstance() {
    return VFSHolder.INSTANCE;
  }

  /**
   * 将自定义VFS类添加到集合USER_IMPLEMENTATIONS
   * Adds the specified class to the list of {@link VFS} implementations. Classes added in this
   * manner are tried in the order they are added and before any of the built-in implementations.
   *
   * @param clazz The {@link VFS} implementation class to add.
   */
  public static void addImplClass(Class<? extends VFS> clazz) {
    if (clazz != null) {
      USER_IMPLEMENTATIONS.add(clazz);
    }
  }

  /**
   * 基于当前线程的类加载器，通过类名获取类型对象
   * 异常返回null
   * Get a class by name. If the class is not found then return null.
   */
  protected static Class<?> getClass(String className) {
    try {
      return Thread.currentThread().getContextClassLoader().loadClass(className);
//      return ReflectUtil.findClass(className);
    } catch (ClassNotFoundException e) {
      if (log.isDebugEnabled()) {
        log.debug("Class not found: " + className);
      }
      return null;
    }
  }

  /**
   * 基于反射，获取指定Class指定方法名指定参数类型的方法反射对象。
   * 异常返回null
   * Get a method by name and parameter types. If the method is not found then return null.
   *
   * @param clazz The class to which the method belongs.
   * @param methodName The name of the method.
   * @param parameterTypes The types of the parameters accepted by the method.
   */
  protected static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
    if (clazz == null) {
      return null;
    }
    try {
      //获取方法反射对象
      return clazz.getMethod(methodName, parameterTypes);
    } catch (SecurityException e) {
      log.error("Security exception looking for method " + clazz.getName() + "." + methodName + ".  Cause: " + e);
      return null;
    } catch (NoSuchMethodException e) {
      log.error("Method not found " + clazz.getName() + "." + methodName + "." + methodName + ".  Cause: " + e);
      return null;
    }
  }

  /**
   * 执行方法
   * Invoke a method on an object and return whatever it returns.
   *
   * @param method The method to invoke.
   * @param object The instance or class (for static methods) on which to invoke the method.
   * @param parameters The parameters to pass to the method.
   * @return Whatever the method returns.
   * @throws IOException If I/O errors occur
   * @throws RuntimeException If anything else goes wrong
   */
  @SuppressWarnings("unchecked")
  protected static <T> T invoke(Method method, Object object, Object... parameters)
      throws IOException, RuntimeException {
    try {
      //执行方法
      return (T) method.invoke(object, parameters);
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof IOException) {
        throw (IOException) e.getTargetException();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * 基于 类加载器 获取类路径下指定路径的 URL 集合。
   * Get a list of {@link URL}s from the context classloader for all the resources found at the
   * specified path.
   *
   * @param path The resource path.
   * @return A list of {@link URL}s, as returned by {@link ClassLoader#getResources(String)}.
   * @throws IOException If I/O errors occur
   */
  protected static List<URL> getResources(String path) throws IOException {
    return Collections.list(Thread.currentThread().getContextClassLoader().getResources(path));
  }

  /**
   * 判断是否为合法的 VFS，由子类实现
   * Return true if the {@link VFS} implementation is valid for the current environment.
   */
  public abstract boolean isValid();

  /**
   * 递归的列出所有的资源们
   * Recursively list the full resource path of all the resources that are children of the
   * resource identified by a URL.
   *
   * @param url The URL that identifies the resource to list.
   * @param forPath The path to the resource that is identified by the URL. Generally, this is the
   *            value passed to {@link #getResources(String)} to get the resource URL.
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  protected abstract List<String> list(URL url, String forPath) throws IOException;

  /**
   * 获得指定路径下的所有资源，底层调用 {@link #list(URL, String)}实现的递归查找资源
   * getResources(path)方法只实现了当前路径对应的 url 集合查找
   * Recursively list the full resource path of all the resources that are children of all the
   * resources found at the specified path.
   *
   * @param path The path of the resource(s) to list.路径以"/"分隔
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  public List<String> list(String path) throws IOException {
    List<String> names = new ArrayList<>();
    for (URL url : getResources(path)) {
      names.addAll(list(url, path));
    }
    return names;
  }
}
