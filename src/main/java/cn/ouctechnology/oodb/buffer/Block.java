package cn.ouctechnology.oodb.buffer;

import cn.ouctechnology.oodb.util.ByteUtil;

import static cn.ouctechnology.oodb.constant.Constants.*;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-10-06 13:35
 * @description: �������飬����ṹ��Ԫ
 **/
public class Block {
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
}
