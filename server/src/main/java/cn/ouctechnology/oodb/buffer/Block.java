package cn.ouctechnology.oodb.buffer;

import cn.ouctechnology.oodb.btree.BTree;
import cn.ouctechnology.oodb.btree.BTreeInnerNode;
import cn.ouctechnology.oodb.btree.BTreeLeafNode;
import cn.ouctechnology.oodb.btree.BTreeNode;
import cn.ouctechnology.oodb.catalog.attribute.Attribute;
import cn.ouctechnology.oodb.exception.DbException;
import cn.ouctechnology.oodb.util.ByteUtil;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static cn.ouctechnology.oodb.constant.Constants.*;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-10-06 13:35
 * @description: �������飬����ṹ��Ԫ
 **/
public class Block implements Cloneable {
    //��������Ϊһ��byte����
    byte[] data = new byte[BLOCK_SIZE];

    //��������
    BlockKey blockKey = new BlockKey();
    //��־�������Ƿ���δͬ����������
    boolean dirty = false;

    //����˫�������ָ��
    Block next = null;
    Block pre = null;

    //����ƫ�Ƶ�ַ
    int dataOffset = 0;

    //�ǹ�ƽ�Ķ�д��
    ReentrantReadWriteLock lock = new ReentrantReadWriteLock(false);
    //����������block���߳�
    Thread thread;
    //block���������ݣ���������ع�
    byte[] oldData;


    /**
     * ���ڶ��������������ȫ������
     */
    public byte[] readData() {
        dataOffset = BLOCK_SIZE;
        return data;
    }


    /**
     * ��data�����е�����д�뵽������offsetƫ�ƴ�
     *
     * @param data ��д������
     * @param size ��д�����ݴ�С
     */
    public void writeData(byte data[], int size) {
        if (dataOffset + size >= BLOCK_SIZE) {
            throw new IndexOutOfBoundsException("the data size is larger than the left capacity");
        }
        if (size >= 0) System.arraycopy(data, 0, this.data, dataOffset, size);
        dirty = true;
        dataOffset += size;
    }


    /**
     * �������е�ָ��λ�ö���һ������
     */
    public int readInt() {
        int res = ByteUtil.getInt(data, dataOffset);
        dataOffset += SIZE_INT;
        return res;
    }

    /**
     * ������ָ��λ�ô�д��һ������
     *
     * @param num ��д������
     */
    public void writeInt(int num) {
        dataOffset = ByteUtil.getBytes(num, data, dataOffset);
        dirty = true;
    }

    /**
     * �������е�ָ��λ�ö���һ��������
     */
    public float readFloat() {
        float res = ByteUtil.getFloat(data, dataOffset);
        dataOffset += SIZE_FLOAT;
        return res;
    }

    /**
     * ������ָ��λ�ô�д��һ��������
     *
     * @param num ��д�븡����
     */
    public void writeFloat(float num) {
        dataOffset = ByteUtil.getBytes(num, data, dataOffset);
        dirty = true;
    }


    /**
     * �������е�ָ��λ�ö���һ������Ϊlength���ַ���
     *
     * @param length �ַ�������
     * @return
     */
    public String readString(int length) {//��length��ָ��attribute�ĳ����Ǽ����ֽڡ�
        String res = ByteUtil.getString(data, dataOffset, length);
        //����char�������ݣ��洢1����Ҫ2�ֽ�
        dataOffset += 2 * length;
        return res;
    }


    /**
     * ������ָ��λ�ô�д��һ���ַ���
     *
     * @param str    ��д���ַ���
     * @param length �ַ�������
     */
    public void writeString(String str, int length) {//��length��ָ����string��0���������ֽڣ�
        dataOffset = ByteUtil.getBytes(str, data, dataOffset, length);
        dirty = true;
    }

    public int getDataOffset() {
        return dataOffset;
    }

    public void setDataOffset(int dataOffset) {
        this.dataOffset = dataOffset;
    }

    public void skipDataOffset(int skip) {
        dataOffset += skip;
    }

    public BlockKey getBlockKey() {
        return blockKey;
    }

    public <TKey extends Comparable<TKey>> BTreeNode<TKey> readBTreeNode(BTree<TKey> bTree) {
        setDataOffset(0);
        int isLeaf = readInt();
        int parent = readInt();
        int right = readInt();
        int left = readInt();
        int keyCount = readInt();
        Attribute attribute = bTree.getAttribute();
        if (isLeaf == 1) {
            BTreeLeafNode<TKey> bTreeLeafNode = new BTreeLeafNode<>(bTree, blockKey.blockOffset, keyCount, parent, left, right);
            for (int i = 0; i < keyCount; i++) {
                Object key = attribute.read(this);
                bTreeLeafNode.setKey(i, (TKey) key);
            }
            for (int i = 0; i < keyCount; i++) {
                bTreeLeafNode.setValue(i, readInt());
            }
            return bTreeLeafNode;
        }
        BTreeInnerNode<TKey> bTreeInnerNode = new BTreeInnerNode<>(bTree, blockKey.blockOffset, keyCount, parent, left, right);
        for (int i = 0; i < keyCount; i++) {
            Object key = attribute.read(this);
            bTreeInnerNode.setKey(i, (TKey) key);
        }
        for (int i = 0; i <= keyCount; i++) {
            int child = readInt();
            bTreeInnerNode.setChild(i, child);
        }
        return bTreeInnerNode;
    }

    /**
     * ���澵��
     */
    public void setImage() {
        if (oldData == null) oldData = new byte[BLOCK_SIZE];
        System.arraycopy(data, 0, oldData, 0, BLOCK_SIZE);
    }

    /**
     * �ָ�����
     */
    public void rollBackImage() {
        System.arraycopy(oldData, 0, data, 0, BLOCK_SIZE);
    }

    public ReentrantReadWriteLock getLock() {
        return lock;
    }

    public boolean isLocked() {
        return lock != null && (lock.isWriteLocked() || lock.getReadHoldCount() > 0);
    }

    @Override
    protected Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new DbException(e);
        }
    }
}
