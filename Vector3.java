public class Vector3 {
	public double x;
	public double y;
	public double z;

	public Vector3() {
		this(0, 0, 0);
	}

	public Vector3(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public static double getDotProductFor(Vector3 v1, Vector3 v2) {
		double xResult = v1.x * v2.x;
		double yResult = v1.y * v2.y;
		double zResult = v1.z * v2.z;
		return xResult + yResult + zResult;
	}

	public static Vector3 getVectorFromTo(Vector3 source, Vector3 destination) {
		double xResult = destination.x - source.x;
		double yResult = destination.y - source.y;
		double zResult = destination.z - source.z;
		return new Vector3(xResult, yResult, zResult);
	}

	public double getMagnitude() {
		return Math.sqrt((x * x) + (y * y) + (z * z));
	}

	public Vector3 getNormalized() {
		double mag = getMagnitude();
		double xNormalized = x / mag;
		double yNormalized = y / mag;
		double zNormalized = z / mag;
		return new Vector3(xNormalized, yNormalized, zNormalized);
	}
}
