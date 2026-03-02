package su.rumishistem.rumi_backup_system.Tool;

public class Binary {
	/**
	 * Intを4バイトでビッグエンディアンなバイナリに変換します。
	 * 
	 * @param num 数値
	 */
	public static byte[] int_to_binary(int num) {
		byte[] b = new byte[4];
		b[0] = (byte)((num >>> 24) & 0xFF);
		b[1] = (byte)((num >>> 16) & 0xFF);
		b[2] = (byte)((num >>> 8) & 0xFF);
		b[3] = (byte)(num & 0xFF);
		return b;
	}

	/**
	 * ビッグエンディアンな4バイトからIntに変換します
	 * 
	 * @param b バイナリ
	 */
	public static int binary_to_int(byte[] b) {
		return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
	}

	/**
	 * Longを8バイトでビッグエンディアンなバイナリに変換します。
	 * 
	 * @param num 数値
	 */
	public static byte[] long_to_binary(long num) {
		byte[] b = new byte[8];
		b[0] = (byte)((num >>> 56) & 0xFF);
		b[1] = (byte)((num >>> 48) & 0xFF);
		b[2] = (byte)((num >>> 40) & 0xFF);
		b[3] = (byte)((num >>> 32) & 0xFF);
		b[4] = (byte)((num >>> 24) & 0xFF);
		b[5] = (byte)((num >>> 16) & 0xFF);
		b[6] = (byte)((num >>> 8) & 0xFF);
		b[7] = (byte)(num & 0xFF);
		return b;
	}

	/**
	 * ビッグエンディアンな4バイトからLongに変換します
	 * 
	 * @param b バイナリ
	 */
	public static long binary_to_long(byte[] b) {
		return ((long)(b[0] & 0xFF) << 56) | ((long)(b[1] & 0xFF) << 48) | ((long)(b[2] & 0xFF) << 40) | ((long)(b[3] & 0xFF) << 32) | ((long)(b[4] & 0xFF) << 24) | ((long)(b[5] & 0xFF) << 16) | ((long)(b[6] & 0xFF) << 8) | ((long)(b[7] & 0xFF));
	}
}
