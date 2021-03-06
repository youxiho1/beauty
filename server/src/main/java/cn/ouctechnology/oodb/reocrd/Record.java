package cn.ouctechnology.oodb.reocrd;

import cn.ouctechnology.oodb.btree.BTree;
import cn.ouctechnology.oodb.buffer.Block;
import cn.ouctechnology.oodb.buffer.Buffer;
import cn.ouctechnology.oodb.catalog.Catalog;
import cn.ouctechnology.oodb.catalog.Index;
import cn.ouctechnology.oodb.catalog.PrimaryKey;
import cn.ouctechnology.oodb.catalog.Table;
import cn.ouctechnology.oodb.catalog.attribute.Attribute;
import cn.ouctechnology.oodb.exception.DbException;
import cn.ouctechnology.oodb.util.JudgeUtil;
import cn.ouctechnology.oodb.util.WhereClauseUtil;
import cn.ouctechnology.oodb.util.where.Op;
import cn.ouctechnology.oodb.util.where.WhereNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static cn.ouctechnology.oodb.constant.Constants.*;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-10-07 10:21
 * @description: 封装对记录的CRUD底层API
 **/
public class Record {

    /**
     * 创建表文件
     * TODO 此处就写了4字节的数据就浪费了一个block
     */
    public static void create(String tableName) {
        File file = new File(DB_PATH + tableName + RECORD_SUFFIX);
        if (file.exists()) {
            file.delete();
            //throw new IllegalArgumentException("The table:" + tableName + " is already exited in the database!");
        }
        Block block = Buffer.getBlock(tableName, 0, WRITE);
        block.setDataOffset(0);
        //文件前四个字节写入空闲链表结束标志
        block.writeInt(FREE_LIST_EOF);
    }

    public static void drop(String tableName) {
        File file = new File(DB_PATH + tableName + RECORD_SUFFIX);
//        if (!file.exists()) {
//            throw new IllegalArgumentException("The table:" + tableName + " is already exited in the database!");
//        }
        file.delete();
    }


    /**
     * 插入元组
     *
     * @param tableName 表名
     * @param tuple     元组
     */
    public static int insert(String tableName, Tuple tuple) {
        //主键检查
        PrimaryKey primaryKey = Catalog.getPrimaryKey(tableName);
        if (primaryKey != null) {
            String name = primaryKey.getName();
            Comparable value = (Comparable) tuple.get(name);
            if (value != null) {
                Index index = Catalog.getIndexByColumnName(tableName, name);
                List search = index.getbTree().search(value, Op.Equality);
                if (search != null && search.size() > 0) {
                    throw new DbException("the primary is duplicate");
                }
                if (primaryKey.getPolicy() == PrimaryKey.PrimaryKeyPolicy.AUTO_INCREASE) {
                    Table table = Catalog.getTable(tableName);
                    int maxId = table.getMaxId();
                    table.setMaxId(Math.max(maxId, (Integer) value));
                }
            } else {
                //生成主键
                switch (primaryKey.getPolicy()) {
                    case UUID:
                        Attribute attribute = Catalog.getAttribute(tableName, name);
                        int length = attribute.getLength() / 2;
                        String uuid = UUID.randomUUID().toString();
                        //截断
                        if (uuid.length() > length) uuid = uuid.substring(length);
                        tuple.add(name, uuid);
                        break;
                    case AUTO_INCREASE:
                        Table table = Catalog.getTable(tableName);
                        int maxId = table.getMaxId();
                        maxId++;
                        table.setMaxId(maxId);
                        tuple.add(name, maxId);
                        break;
                    case ASSIGN:
                        throw new DbException("the primary key can not bt null");
                }
            }
        }
        //获取block
        Block block = getWriteBlock(tableName);
        //写入有效标志位
        block.writeInt(TUPLE_AVAILABLE);
        //写入具体的属性
        tuple.write(block, tableName);
        //添加元组数量
        Catalog.addTupleNum(tableName);
        //维护索引
        List<Index> indexes = Catalog.getIndexes(tableName);
        int dataOffset = block.getDataOffset() - Catalog.getTupleLength(tableName);
        int offset = block.getBlockKey().blockOffset * BLOCK_SIZE + dataOffset;
        for (Index index : indexes) {
            index.getbTree().insert((Comparable) tuple.get(index.getColumnName()), offset);
        }
        return SINGLE_AFFECTED;
    }


    /**
     * 更新元组信息
     *
     * @param tableName 表名
     * @param tuple     更新后的值
     * @param whereTree 选择条件
     */
    public static int update(String tableName, String tableAlias, Tuple tuple, WhereNode whereTree) {
        //先检查是否能够使用索引更新
        WhereClauseUtil.IndexStruct index = WhereClauseUtil.getIndex(tableName, whereTree);
        if (index != null) return updateByIndex(tableName, tuple, index);

        List<String> whereFieldList = new ArrayList<>();
        if (whereTree != null) {
            WhereClauseUtil.getWhereFieldList(whereTree, whereFieldList);
            whereFieldList = whereFieldList.stream().map(s -> s.substring(s.indexOf(".") + 1)).distinct().collect(Collectors.toList());
        }
        int res = 0;
        int tupleNum = Catalog.getTupleNum(tableName);
        int tupleOffset = 0;
        int tupleScan = 0;
        int tupleLength = Catalog.getTupleLength(tableName);
        while (tupleScan < tupleNum) {
            Block block = Record.getBlock(tableName, tupleOffset, READ);
            int isAvailable = block.readInt();
            int dataOffset = block.getDataOffset();
            if (isAvailable == TUPLE_AVAILABLE) {
                Tuple whereTuple = Record.readTuple(block, tableName, tableAlias, whereFieldList);
                //恢复位置
                block.setDataOffset(dataOffset);
                //判断where
                if (JudgeUtil.whereJudge(whereTuple, whereTree)) {
                    block = Record.getBlock(tableName, tupleOffset, WRITE);
                    block.setDataOffset(dataOffset);
                    tuple.write(block, tableName);
                    res++;
                }
                tupleScan++;
            }

            tupleOffset++;
            block.setDataOffset(dataOffset + tupleLength);
        }
        return res;
    }


    public static int updateByIndex(String tableName, Tuple tuple, WhereClauseUtil.IndexStruct indexStruct) {
        BTree bTree = indexStruct.index.getbTree();
        List<Integer> offsetList = bTree.search(indexStruct.value, indexStruct.op);
        Comparable value = (Comparable) tuple.get(indexStruct.column);
        if (value != null) {
            List search = bTree.search(value, Op.Equality);
            if (search != null && search.size() > 0) throw new DbException("the key is duplicate");
        }
        for (Integer offset : offsetList) {
            int blockNo = offset / BLOCK_SIZE;
            int dataOffset = offset % BLOCK_SIZE;
            Block block = Buffer.getBlock(tableName, blockNo, WRITE);
            block.setDataOffset(dataOffset);
            tuple.write(block, tableName);
        }
        //更新索引
        if (value != null) {
            bTree.delete(indexStruct.value, indexStruct.op);
            for (Integer offset : offsetList) {
                List search = bTree.search(value, Op.Equality);
                if (search == null || search.size() <= 0) {
                    bTree.insert(value, offset);
                } else {
                    throw new DbException("the key is duplicate");
                }
            }
        }
        return offsetList.size();
    }

    /**
     * 全量删除，清空表
     */
    public static int delete(String tableName) {
        int tupleNum = Catalog.getTupleNum(tableName);
        //设置元组数量为0
        Catalog.getTable(tableName).setTupleNum(0);
        //将涉及到的buffer内容写入磁盘
        Buffer.writeToDisk(tableName);
        File file = new File(DB_PATH + tableName + RECORD_SUFFIX);
        if (!file.exists()) {
            throw new IllegalArgumentException("The table:" + tableName + " is already exited in the database!");
        }
        //清空文件
        try {
            file.delete();
            file.createNewFile();
        } catch (IOException e) {
            //todo 异常处理
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        //重新写入初始标志
        Block block = Buffer.getBlock(tableName, 0, WRITE);
        block.setDataOffset(0);
        //文件前四个字节写入空闲链表结束标志
        block.writeInt(FREE_LIST_EOF);
        return tupleNum;
    }

    /**
     * 有条件式删除
     */
    public static int delete(String tableName, String tableAlias, WhereNode whereTree) {
        WhereClauseUtil.IndexStruct index = WhereClauseUtil.getIndex(tableName, whereTree);
        if (index != null) return deleteByIndex(tableName, index);

        List<String> whereFieldList = new ArrayList<>();
        WhereClauseUtil.getWhereFieldList(whereTree, whereFieldList);
        whereFieldList = whereFieldList.stream().map(s -> s.substring(s.indexOf(".") + 1)).distinct().collect(Collectors.toList());

        int res = 0;
        int tupleNum = Catalog.getTupleNum(tableName);
        int tupleOffset = 0;
        int tupleScan = 0;
        int tupleLength = Catalog.getTupleLength(tableName);
        while (tupleScan < tupleNum) {
            Block block = Record.getBlock(tableName, tupleOffset, READ);
            int isAvailable = block.readInt();
            int dataOffset = block.getDataOffset();
            if (isAvailable == TUPLE_AVAILABLE) {
                Tuple whereTuple = Record.readTuple(block, tableName, tableAlias, whereFieldList);
                //恢复位置
                block.setDataOffset(dataOffset);
                //判断where
                if (JudgeUtil.whereJudge(whereTuple, whereTree)) {
                    //读取第一行，空闲链表的起始位置
                    Block firstBlock = Buffer.getBlock(tableName, 0, WRITE);
                    firstBlock.setDataOffset(0);
                    int firstTupleOffset = firstBlock.readInt();

                    firstBlock.setDataOffset(0);
                    firstBlock.writeInt(tupleOffset);
                    //升级写锁
                    block = Record.getBlock(tableName, tupleOffset, WRITE);
                    block.setDataOffset(dataOffset - SIZE_INT);
                    block.writeInt(firstTupleOffset);

                    res++;
                }
                tupleScan++;
            }

            tupleOffset++;
            block.setDataOffset(dataOffset + tupleLength);
        }
        //设置数量减少
        Catalog.getTable(tableName).setTupleNum(tupleNum - res);
        return res;
    }

    private static int deleteByIndex(String tableName, WhereClauseUtil.IndexStruct indexStruct) {
        BTree bTree = indexStruct.index.getbTree();
        List<Integer> offsetList = bTree.search(indexStruct.value, indexStruct.op);
        for (Integer offset : offsetList) {
            offset -= SIZE_INT;
            int blockNo = offset / BLOCK_SIZE;
            int dataOffset = offset % BLOCK_SIZE;
            Block block = Buffer.getBlock(tableName, blockNo, WRITE);
            block.setDataOffset(dataOffset);
            //读取第一行，空闲链表的起始位置
            Block firstBlock = Buffer.getBlock(tableName, 0, WRITE);
            firstBlock.setDataOffset(0);
            int firstTupleOffset = firstBlock.readInt();
            int writePointer = firstTupleOffset > 0 ? firstTupleOffset : FREE_LIST_EOF;

            int tupleLength = Catalog.getTupleLength(tableName);
            //每个block最多容纳多少个tuple
            int tupleInABlock = BLOCK_SIZE / (tupleLength + SIZE_INT);
            int tupleOffset = dataOffset / (tupleLength + SIZE_INT) + (blockNo * tupleInABlock);

            firstBlock.setDataOffset(0);
            firstBlock.writeInt(tupleOffset - 1);
            block.setDataOffset(dataOffset);
            block.writeInt(writePointer);
        }
        //删除索引
        bTree.delete(indexStruct.value, indexStruct.op);
        //设置数量减少
        int tupleNum = Catalog.getTupleNum(tableName);
        Catalog.getTable(tableName).setTupleNum(tupleNum - offsetList.size());
        return offsetList.size();
    }


    /**
     * 从block中读取一个tuple
     *
     * @param block
     * @param tableName
     */
    public static Tuple readTuple(Block block, String tableName, String tableAlias) {
        Tuple tuple = new Tuple();
        List<Attribute> attributes = Catalog.getAttributes(tableName);
        for (Attribute attribute : attributes) {
            tuple.add(tableAlias + "." + attribute.getName(), attribute.read(block));
        }
        return tuple;
    }

    /**
     * 从block中读取一个tuple
     *
     * @param block
     * @param tableName
     */
    public static Tuple readTuple(Block block, String tableName, String tableAlias, List<String> fieldList) {
        Tuple tuple = new Tuple();
        int dataOffset = block.getDataOffset();
        for (String field : fieldList) {
            int attributeOffset = Catalog.getAttributeOffset(tableName, field);
            //重新归位
            block.setDataOffset(dataOffset + attributeOffset);
            Attribute attribute = Catalog.getAttribute(tableName, field);
            tuple.add(tableAlias + "." + field, attribute.read(block));
        }
        return tuple;
    }

    /**
     * 获取一个可写入的block以及写入位置的偏移量,流程如下：
     * 1、先找空闲链表
     * 2、在找最后一个block
     */
    private static Block getWriteBlock(String tableName) {
        //读取第一行，空闲链表的起始位置
        Block block = Buffer.getBlock(tableName, 0, WRITE);
        block.setDataOffset(0);
        int tupleOffset = block.readInt();
        //有空闲链表
        if (tupleOffset != FREE_LIST_EOF) {
            Block nextFreeBlock = getBlock(tableName, tupleOffset, WRITE);
            //保存旧的offset
            int oldOffset = nextFreeBlock.getDataOffset();
            //读取下一块空闲链表
            tupleOffset = nextFreeBlock.readInt();
            //更新链表
            block.setDataOffset(0);
            block.writeInt(tupleOffset);
            //恢复原值
            nextFreeBlock.setDataOffset(oldOffset);
            return nextFreeBlock;
        }
        int tupleNum = Catalog.getTupleNum(tableName);
        return getBlock(tableName, tupleNum, WRITE);
    }

    /**
     * 根据表名和元组序号获取元组所在的block
     *
     * @param tableName
     * @param tupleOffset
     */
    public static Block getBlock(String tableName, int tupleOffset, int mode) {
        int tupleLength = Catalog.getTupleLength(tableName);
        //每个block最多容纳多少个tuple
        int tupleInABlock = BLOCK_SIZE / (tupleLength + SIZE_INT);
        //最后一个元组在哪个区块
        int blockOffset = (tupleOffset + 1) / tupleInABlock;
        //最后一个元组在最后一个区块的块内偏移量
        int dataOffset = (SIZE_INT + tupleLength) * ((tupleOffset + 1) % tupleInABlock);
        Block resBlock = Buffer.getBlock(tableName, blockOffset, mode);
        resBlock.setDataOffset(dataOffset);
        return resBlock;
    }
}
