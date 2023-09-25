/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.cursor.defaults;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 实现 Cursor 接口，默认唯一的 Cursor 实现类
 * This is the default implementation of a MyBatis Cursor.
 * This implementation is not thread safe.
 *
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public class DefaultCursor<T> implements Cursor<T> {

  // ResultSetHandler stuff
  private final DefaultResultSetHandler resultSetHandler;
  private final ResultMap resultMap;
  private final ResultSetWrapper rsw;
  private final RowBounds rowBounds;
  /**
   * ObjectWrapperResultHandler对象
   */
  private final ObjectWrapperResultHandler<T> objectWrapperResultHandler = new ObjectWrapperResultHandler<>();

  /**
   * CursorIterator对象，游标迭代器
   */
  private final CursorIterator cursorIterator = new CursorIterator();
  /**
   * 是否已获取游标迭代器。是否开始迭代
   * {@link #iterator()}
   */
  private boolean iteratorRetrieved;

  /**
   * 游标对象状态，初始化创建
   */
  private CursorStatus status = CursorStatus.CREATED;
  /**
   * 已完成映射的行数
   */
  private int indexWithRowBound = -1;

  /**
   * 游标对象状态枚举。DefaultCursor 的内部枚举类
   */
  private enum CursorStatus {

    /**
     * 创建，还未使用游标
     * A freshly created cursor, database ResultSet consuming has not started
     */
    CREATED,
    /**
     * 打开，开始使用游标
     * A cursor currently in use, database ResultSet consuming has started
     */
    OPEN,
    /**
     * 已关闭，并未完全消费
     * A closed cursor, not fully consumed
     */
    CLOSED,
    /**
     * 已关闭，并且完全消费
     * A fully consumed cursor, a consumed cursor is always closed
     */
    CONSUMED
  }

  public DefaultCursor(DefaultResultSetHandler resultSetHandler, ResultMap resultMap, ResultSetWrapper rsw, RowBounds rowBounds) {
    this.resultSetHandler = resultSetHandler;
    this.resultMap = resultMap;
    this.rsw = rsw;
    this.rowBounds = rowBounds;
  }

  @Override
  public boolean isOpen() {
    return status == CursorStatus.OPEN;
  }

  @Override
  public boolean isConsumed() {
    return status == CursorStatus.CONSUMED;
  }

  @Override
  public int getCurrentIndex() {
    return rowBounds.getOffset() + cursorIterator.iteratorIndex;
  }

  /**
   * 获取迭代器，从cursorIterator成员属性直接返回
   *
   * 通过 iteratorRetrieved 属性，保证有且仅返回一次 cursorIterator 对象。
   * 一个默认游标对象只能获取一次迭代器。
   * @return
   */
  @Override
  public Iterator<T> iterator() {
    //1.如果 iteratorRetrieved 为true，即已通过此方法获取过迭代器，抛出异常
    if (iteratorRetrieved) {
      throw new IllegalStateException("Cannot open more than one iterator on a Cursor");
    }
    //2.如果游标状态为关闭，抛出异常
    if (isClosed()) {
      throw new IllegalStateException("A Cursor is already closed.");
    }
    //3.标记已经获取过迭代器
    iteratorRetrieved = true;
    //4返回游标迭代器
    return cursorIterator;
  }

  /**
   * 关闭游标
   */
  @Override
  public void close() {
    //1.已关闭，直接返回
    if (isClosed()) {
      return;
    }

    //2.获取结果集
    ResultSet rs = rsw.getResultSet();
    try {
      //3.关闭结果集
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    } finally {
      //4.游标状态置为关闭
      status = CursorStatus.CLOSED;
    }
  }

  /**
   * 遍历下一条记录获取结果对象。由迭代器调用该方法
   * 底层调用 {@link #fetchNextObjectFromDatabase()} 实现
   * @return 结果对象
   */
  protected T fetchNextUsingRowBound() {
    //1.委托 fetchNextObjectFromDatabase() 方法遍历下一条记录
    T result = fetchNextObjectFromDatabase();
    //2.循环跳过 rowBounds 的索引。第一次调用该方法时会执行
    while (result != null && indexWithRowBound < rowBounds.getOffset()) {
      result = fetchNextObjectFromDatabase();
    }
    //3.返回记录
    return result;
  }

  /**
   * 底层的方法遍历下一条记录获取结果对象
   * @return 结果对象
   */
  protected T fetchNextObjectFromDatabase() {
    //1.如果已经关闭，返回null
    if (isClosed()) {
      return null;
    }

    try {
      //2.游标状态置为open
      status = CursorStatus.OPEN;
      //3.如果结果集未关闭，则委托resultSetHandler来处理结果映射，
      // 处理一行记录并将结果添加到 objectWrapperResultHandler。虽然调用处理多行，但 ResultHandler 处理结果时暂停了后续处理
      if (!rsw.getResultSet().isClosed()) {
        resultSetHandler.handleRowValues(rsw, resultMap, objectWrapperResultHandler, RowBounds.DEFAULT, null);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    //4.将结果对象赋值给 next
    T next = objectWrapperResultHandler.result;
    //5.结果对象不为空，indexWithRowBound计数+1
    if (next != null) {
      indexWithRowBound++;
    }
    // No more object or limit reached
    //6.如果结果为空 即没有更多记录 或达到了行限制，则关闭游标，并设置游标状态
    if (next == null || getReadItemsCount() == rowBounds.getOffset() + rowBounds.getLimit()) {
      close();
      status = CursorStatus.CONSUMED;
    }
    //7.置空 objectWrapperResultHandler.result
    objectWrapperResultHandler.result = null;

    //8.返回下一条结果
    return next;
  }

  /**
   * 判断游标对象是否已经关闭
   * @return
   */
  private boolean isClosed() {
    return status == CursorStatus.CLOSED || status == CursorStatus.CONSUMED;
  }

  private int getReadItemsCount() {
    return indexWithRowBound + 1;
  }

  /**
   * DefaultCursor 的内部静态类，实现 ResultHandler 接口
   * @param <T>
   */
  private static class ObjectWrapperResultHandler<T> implements ResultHandler<T> {

    /**
     * 结果对象
     */
    private T result;

    @Override
    public void handleResult(ResultContext<? extends T> context) {
      //1.从结果对象上下文获取结果对象，并初始化result属性
      this.result = context.getResultObject();
      //2.暂停上下文。暂停 DefaultResultSetHandler 在向下遍历下一条记录
      // 从而实现每次在调用 CursorIterator#hasNext() 方法，只遍历一行 ResultSet 的记录
      context.stop();
    }
  }

  /**
   * DefaultCursor 的内部类，实现 Iterator 接口，游标的迭代器实现类
   */
  private class CursorIterator implements Iterator<T> {

    /**
     * 迭代当前指向元素。结果对象，提供给 {@link #next()} 返回
     * Holder for the next object to be returned
     */
    T object;

    /**
     * 索引位置
     * Index of objects returned using next(), and as such, visible to users.
     */
    int iteratorIndex = -1;

    /**
     * 是否有下一个结果对象元素
     * 1.如果object为空，则该方法是先获取下一条记录，然后在判断是否存在下一条记录。
     * 2.如果object不为空，则直接true
     * @return
     */
    @Override
    public boolean hasNext() {
      //1.如果 object 为空，则遍历下一条记录
      if (object == null) {
        object = fetchNextUsingRowBound();
      }
      //2.判断 object 是否非空
      return object != null;
    }

    @Override
    public T next() {
      // Fill next with object fetched from hasNext()
      //1.next结果指向object属性（缓存迭代器指向的当前元素，用于返回）
      T next = object;

      //2.如果 next 为空，则遍历下一条记录
      if (next == null) {
        next = fetchNextUsingRowBound();
      }

      //3.如果 next 不为空，说明有记录，则进行返回
      if (next != null) {
        //3.1置空object属性(消耗迭代器当前指向元素，清空迭代器指针的对象，防止hasNext返回true)
        object = null;
        //3.2增加 iteratorIndex
        iteratorIndex++;
        //3.3返回next
        return next;
      }
      //4.如果 next 为空，则没有后续记录，抛出异常
      throw new NoSuchElementException();
    }

    /**
     * 不支持移除元素
     */
    @Override
    public void remove() {
      throw new UnsupportedOperationException("Cannot remove element from Cursor");
    }
  }
}
