package cn.ouctechnology.oodb.buffer;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

import static cn.ouctechnology.oodb.constant.Constants.*;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-10-06 13:35
 * @description: ������������LRU�û��㷨�����ݽṹΪ˫������+HashSet
 **/
public class Buffer {
    private static Logger logger = LoggerFactory.getLogger(Buffer.class);

    //�洢�����HashMap��keyΪ�ļ����ƺ��������ļ���ƫ���������
    private static Map<BlockKey, Block> blockMap = new HashMap<>(MAX_NUM_OF_BLOCKS);
    //�������е�ǰ�������Ŀ
    private static int size = 0;
    //ͷ����
    private static Block head;
    //β����
    private static Block tail;


    /**
     * ��ʼ��ͷβ���
     */
    public static void init() {
        head = new Block();
        tail = new Block();
        head.next = tail;
        head.pre = null;
        tail.next = null;
        tail.pre = head;
    }


    /**
     * �رջ�������ͬ��������
     */
    public static void close() {
        Block iterator = head.next;
        while (iterator != tail) {
            if (iterator.dirty) writeToDisk(iterator);
            iterator = iterator.next;
        }
    }

    /**
     * ��tableName�漰��bufferȫ��д�����
     *
     * @param tableName
     */
    public static void writeToDisk(String tableName) {
        Block iterator = head.next;
        while (iterator != tail) {
            if (iterator.blockKey.filename.equals(tableName) && iterator.dirty) writeToDisk(iterator);
            iterator = iterator.next;
        }
    }

    /**
     * �򻺴����������
     *
     * @param blockKey ��������
     */
    private static Block addBlocks(BlockKey blockKey) {
        size++;
        Block block = new Block();
        block.blockKey = blockKey;
        block.dirty = false;
        readFromDisk(blockKey.filename, blockKey.blockOffset, block);
        block.pre = tail.pre;
        tail.pre.next = block;
        tail.pre = block;
        block.next = tail;
        blockMap.put(blockKey, block);
        return block;
    }

    /**
     * ��ָ�������Ƶ������β��������LRU�û�
     *
     * @param block ����
     */
    private static void moveToTail(Block block) {
        block.pre.next = block.next;
        block.next.pre = block.pre;
        block.pre = tail.pre;
        tail.pre.next = block;
        tail.pre = block;
        block.next = tail;
    }

    /**
     * ��ָ������ӻ�������ɾ��
     *
     * @param blockKey ��������
     */
    private static void deleteBlock(BlockKey blockKey) {
        size--;
        Block block = blockMap.get(blockKey);
        //ˢ�µ�����
        if (block.dirty) writeToDisk(block);
        block.pre.next = block.next;
        block.next.pre = block.pre;
        blockMap.remove(blockKey);
    }


    /**
     * �����ļ����Ϳ��Ų�����Ӧ������
     *
     * @param filename �ļ���
     * @param offset   ����
     * @return ��Ӧ����
     */
    public static Block getBlock(String filename, int offset) {
        //�Ȳ�ѯ��������Ƿ��Ѿ������ڴ�
        BlockKey blockKey = new BlockKey(filename, offset);
        if (blockMap.containsKey(blockKey)) {
            //TODO ���ڴ˴�moveToTail������Block�е�ÿ�β�����moveToTail
            Block block = blockMap.get(blockKey);
            moveToTail(block);
            return block;
        }
        //û�е����ڴ�,���һ�������Сδ����ֱ����Ӹ�����
        if (size < MAX_NUM_OF_BLOCKS) {
            return addBlocks(blockKey);
        }
        //���������ˣ�ִ��LRU�滻�㷨
        //ɾ��������δʹ�õ�����
        deleteBlock(head.next.blockKey);
        //�����������
        return addBlocks(blockKey);
    }

    /**
     * ���ļ��е�������鵽ָ������
     *
     * @param filename �ļ���
     * @param offset   ƫ����
     * @param block    ָ������
     */
    private static void readFromDisk(String filename, int offset, Block block) {
        RandomAccessFile raf = null;
        try {
            //raf���Զ���������Ŀ¼
            raf = new RandomAccessFile(DB_PATH + filename + RECORD_SUFFIX, "r");
            //��ȡ���ݣ�����Ƿ�Խ��
            if (raf.length() >= offset * BLOCK_SIZE + BLOCK_SIZE) {
                raf.seek(offset * BLOCK_SIZE);
                raf.read(block.data, 0, BLOCK_SIZE);
            }
        } catch (Exception e) {
            logger.error("read from disk to block error:" + e.getMessage());
        } finally {
            IOUtils.closeQuietly(raf);
        }
    }

    /**
     * ��block�����е�����д�ص��ļ�
     *
     * @param block
     */
    private static void writeToDisk(Block block) {
        if (!block.dirty) {
            return;
        }
        File file = null;
        RandomAccessFile raf = null;
        try {
            file = new File(DB_PATH + block.blockKey.filename + RECORD_SUFFIX);
            //������Ŀ¼
            File parentFile = file.getParentFile();
            if (!parentFile.exists()) parentFile.mkdirs();
            if (!file.exists()) {
                file.createNewFile();
            }
            raf = new RandomAccessFile(file, "rw");
            raf.seek(block.blockKey.blockOffset * BLOCK_SIZE);
            raf.write(block.data);
        } catch (IOException e) {
            logger.error("write to disk error:" + e.getMessage());
        } finally {
            IOUtils.closeQuietly(raf);
        }
    }
}
