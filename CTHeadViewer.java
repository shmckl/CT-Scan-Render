import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.io.*;
import java.net.URL;
import java.util.ResourceBundle;

public class CTHeadViewer implements Initializable {
	@FXML private ImageView topView;
	@FXML private ImageView frontView;
	@FXML private ImageView sideView;

	// Various options for different render methods
	@FXML private Slider topSlider;
	@FXML private Slider frontSlider;
	@FXML private Slider sideSlider;
	@FXML private Slider skinOpacitySlider;
	@FXML private Slider lightSourceLocationSlider;
	@FXML private CheckBox diffuseDebugCheckbox;

	private enum View {
		TOP,
		FRONT,
		SIDE
	}

	// The actual data, axis are: cthead[z][y][x]
	private short[][][] cthead;

	// Used in slice colour. Start at extremes
	private short minShade = Short.MAX_VALUE;
	private short maxShade = Short.MIN_VALUE;

	// Used in volume rendering
	private final float[] skinColor = new float[]{1.0f, 0.79f, 0.6f, 0.12f};
	private final float[] boneColor = new float[]{1.0f, 1.0f, 1.0f, 0.8f};

	// Used in diffuse shading
	private final float ambientLightStrength = 0.1f; // Ambient light is assumed to just be white
	private Vector3 lightSource;
	private final Vector3 lightColor = new Vector3(1, 0.8, 0.3);
	private final Vector3 boneDiffuseColor = new Vector3(0.9, 0.9, 1);
	private boolean diffuseDebuggingColors = false;

	// TODO: Hardcoded size
	private final int CT_x_axis = 256;
	private final int CT_y_axis = 256;
	private final int CT_z_axis = 113;

	// TODO: Are these needed?
	private int topHeight;
	private int topWidth;
	private int frontHeight;
	private int frontWidth;
	private int sideHeight;
	private int sideWidth;

	@Override
	public void initialize(URL location, ResourceBundle resources) {
		try {
			readData();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}

		// Small setup
		topHeight = CT_y_axis;
		topWidth = CT_x_axis;
		frontHeight = CT_z_axis;
		frontWidth = CT_x_axis;
		sideHeight = CT_z_axis;
		sideWidth = CT_y_axis;

		recalculateLightPosition(0);

		// The sliders start with 0 as their min, don't need to touch that
		topSlider.setMax(CT_z_axis - 1);
		frontSlider.setMax(CT_y_axis - 1);
		sideSlider.setMax(CT_x_axis - 1);

		// Set up slice slider events
		topSlider.valueProperty().addListener((obs, old, newVal) -> updateSlice(View.TOP, newVal.intValue()));
		frontSlider.valueProperty().addListener((obs, old, newVal) -> updateSlice(View.FRONT, newVal.intValue()));
		sideSlider.valueProperty().addListener((obs, old, newVal) -> updateSlice(View.SIDE, newVal.intValue()));

		// Set up volume rendering skin opacity slider
		skinOpacitySlider.valueProperty().addListener((obs, old, newVal) -> {
			skinColor[3] = newVal.floatValue();
			System.out.println(skinColor[3]);
			updateVolumeRenders();
		});

		// Diffuse shading stuff

		diffuseDebugCheckbox.selectedProperty().addListener((observable, oldValue, newValue) -> {
			diffuseDebuggingColors = newValue;
			showDiffuseShading();
		});

		lightSourceLocationSlider.valueProperty().addListener((obs, old, newVal) -> {
			recalculateLightPosition(newVal.intValue());
			showDiffuseShading();
		});
	}

	@FXML
	private void updateVolumeRenders() {
		// In order: Red, Green, Blue, Opacity
		float[] ray;
		float[] toAdd;

		// For all our views
		for (View view : View.values()) {
			WritableImage wi = createWritableImageFor(view);
			PixelWriter pw = wi.getPixelWriter();

			int[] maxAxis = getMaxAxisFor(view);
			for (int i = 0; i < maxAxis[0]; i++) {
				for (int j = 0; j < maxAxis[1]; j++) {
					// Reset the ray
					ray = new float[]{0.0f, 0.0f, 0.0f, 0.0f};

					short data;

					// Shoot the ray as deep as possible before opacity becomes 1 or higher
					for (int k = 0; k < getMaxRayLengthFor(view); k++) {

						// Get relevant data
						switch (view) {
							case TOP:
								data = cthead[k][j][i];
								break;
							case FRONT:
								data = cthead[j][k][i];
								break;
							case SIDE:
								data = cthead[j][i][k];
								break;
							default:
								throw new IllegalStateException("Unexpected value: " + view);
						}

						float opacityStrength = 1.0f - ray[3];
						toAdd = dataToRayPart(data, opacityStrength);
						
						if (toAdd[3] > 0.0f) {
							for (int c = 0; c < ray.length; c++) {
								ray[c] += toAdd[c];
							}
						}

						// If maximum opacity reached (i.e. no point to go further) then break out
						if (ray[3] >= 1.0f) {
							//System.out.println("Reached opacity 1.0f");
							break;
						}
					}

					// So that our background is black, and not transparent
					ray[3] = 1.0f;

					// Quick boundary check at the end
					for (int r = 0; r < ray.length; r++) {
						ray[r] = Math.min(1.0f, ray[r]);
					}
					Color rayFinalColor = new Color(ray[0], ray[1], ray[2], ray[3]);

					pw.setColor(i, j, rayFinalColor);
				}
			}

			getImageViewFor(view).setImage(wi);
		}
	}

	@FXML
	private void showDiffuseShading() {
		for (View view : View.values()) {
			WritableImage wi = createWritableImageFor(view);
			PixelWriter pw = wi.getPixelWriter();

			int[] maxAxis = getMaxAxisFor(view);
			for (int i = 0; i < maxAxis[0]; i++) {
				for (int j = 0; j < maxAxis[1]; j++) {

					boolean foundBone = false;
					short data;
					for (int k = 0; k < getMaxRayLengthFor(view); k++) {
						// Get relevant data
						switch (view) {
							case TOP:
								data = cthead[k][j][i];
								break;
							case FRONT:
								data = cthead[j][k][i];
								break;
							case SIDE:
								data = cthead[j][i][k];
								break;
							default:
								throw new IllegalStateException("Unexpected value: " + view);
						}

						if (data > 300 && data < 4096) {
							foundBone = true;

							Vector3 surfaceVector = new Vector3();
							Vector3 surfaceLocation;

							try {
								switch (view) {
									case TOP:
										surfaceVector.x = cthead[k][j][i + 1] - cthead[k][j][i - 1];
										surfaceVector.y = cthead[k][j + 1][i] - cthead[k][j - 1][i];
										surfaceVector.z = cthead[k + 1][j][i] - cthead[k - 1][j][i];
										surfaceLocation = new Vector3(i, j, k);
										break;
									case FRONT:
										surfaceVector.x = cthead[j][k][i + 1] - cthead[j][k][i - 1];
										surfaceVector.y = cthead[j][k + 1][i] - cthead[j][k - 1][i];
										surfaceVector.z = cthead[j + 1][k][i] - cthead[j - 1][k][i];
										surfaceLocation = new Vector3(i, k, j);
										break;
									case SIDE:
										surfaceVector.x = cthead[j][i][k + 1] - cthead[j][i][k - 1];
										surfaceVector.y = cthead[j][i + 1][k] - cthead[j][i - 1][k];
										surfaceVector.z = cthead[j + 1][i][k] - cthead[j - 1][i][k];
										surfaceLocation = new Vector3(k, i, j);
										break;
									default:
										throw new IllegalStateException("Unexpected value: " + view);
								}
							} catch (ArrayIndexOutOfBoundsException ignored) {
								// Skipping this vector
								break;
							}

							Vector3 surfaceNormal = surfaceVector.getNormalized();

							if (diffuseDebuggingColors) {
								double surfaceTotal = Math.abs(surfaceNormal.x) + Math.abs(surfaceNormal.y) + Math.abs(surfaceNormal.z);
								Color normalTest = new Color(
										Math.max(0.0f, surfaceNormal.x / surfaceTotal),
										Math.max(0.0f, surfaceNormal.y / surfaceTotal),
										Math.max(0.0f, surfaceNormal.z / surfaceTotal),
										1.0f);
								pw.setColor(i, j, normalTest);
							} else {
								// Find vector to light source
								Vector3 surfaceToLightNormal = Vector3.getVectorFromTo(surfaceLocation, lightSource).getNormalized();

								// How strongly the light source effects this point (0.0 to 1.0)
								double effect = Math.max(0.0, Vector3.getDotProductFor(surfaceToLightNormal, surfaceNormal));

								Color foo = new Color(
										boneDiffuseColor.x * Math.min(1.0f, effect * lightColor.x + ambientLightStrength * 1.0f),
										boneDiffuseColor.y * Math.min(1.0f, effect * lightColor.y + ambientLightStrength * 1.0f),
										boneDiffuseColor.z * Math.min(1.0f, effect * lightColor.z + ambientLightStrength * 1.0f),
										1.0
								);

								pw.setColor(i, j, foo);
							}

							// We're still in the k-loop for the ray, which we don't need anymore
							break;
						}
					}

					if (!foundBone) {
						pw.setColor(i, j, new Color(0.0f, 0.0f, 0.0f, 1.0f));
					}
				}
			}

			getImageViewFor(view).setImage(wi);
		}
	}

	private void updateSlice(View view, int newSlice) {
		WritableImage wi = createWritableImageFor(view);
		PixelWriter pw = wi.getPixelWriter();

		int[] maxAxis = getMaxAxisFor(view);

		short data;
		for (int i = 0; i < maxAxis[0]; i++) {
			for (int j = 0; j < maxAxis[1]; j++) {
				// Actually render the data
				switch (view) {
					case TOP:
						// TODO: Allow image to be different size to data set
						data = cthead[newSlice][j][i];
						pw.setColor(i, j, dataToColor(data));
						break;
					case FRONT:
						data = cthead[j][newSlice][i];
						pw.setColor(i, j, dataToColor(data));
						break;
					case SIDE:
						data = cthead[j][i][newSlice];
						pw.setColor(i, j, dataToColor(data));
						break;
				}
			}
		}

		getImageViewFor(view).setImage(wi);
	}

	@FXML
	private void setDefaultSlices() {
		updateSlice(View.TOP, 0);
		updateSlice(View.FRONT, 0);
		updateSlice(View.SIDE, 0);
	}

	private void recalculateLightPosition(int newAngle) {
		Vector3 startingPoint = new Vector3(150, 150, 150); // This is the circle center
		int circleRadius = 300;

		startingPoint.x += circleRadius * Math.sin(Math.toRadians(newAngle));
		startingPoint.y += circleRadius * Math.cos(Math.toRadians(newAngle));

		lightSource = startingPoint;
	}

	private int[] getMaxAxisFor(View view) {
		int[] result = new int[2];

		switch (view) {
			case TOP:
				result[0] = CT_x_axis;
				result[1] = CT_y_axis;
				break;
			case FRONT:
				result[0] = CT_x_axis;
				result[1] = CT_z_axis;
				break;
			case SIDE:
				result[0] = CT_y_axis;
				result[1] = CT_z_axis;
				break;
		}

		return result;
	}

	private int getMaxRayLengthFor(View view) {
		switch (view) {
			case TOP:
				return CT_z_axis;
			case FRONT:
				return CT_y_axis;
			case SIDE:
				return CT_x_axis;
			default:
				throw new IllegalStateException("Unexpected value: " + view);
		}
	}

	private WritableImage createWritableImageFor(View view) {
		switch (view) {
			case TOP:
				return new WritableImage(topWidth, topHeight);
			case FRONT:
				return new WritableImage(frontWidth, frontHeight);
			case SIDE:
				return new WritableImage(sideWidth, sideHeight);
			default:
				throw new IllegalStateException("Unexpected value: " + view);
		}
	}

	private ImageView getImageViewFor(View view) {
		switch (view) {
			case TOP:
				return topView;
			case FRONT:
				return frontView;
			case SIDE:
				return sideView;
			default:
				throw new IllegalStateException("Unexpected value: " + view);
		}
	}

	private Color dataToColor(int data) {
		double col = (((float)data - (float)minShade) / ((float)(maxShade - minShade)));
		return new Color(col, col, col, 1.0f);
	}

	private float[] dataToRayPart(int data, float strength) {
		float[] result = new float[]{0.0f, 0.0f, 0.0f, 0.0f};

		if (data < -300) {
			return result;
		} else if (data <= 49) {
			// Skin
			result[0] = skinColor[0] * (skinColor[3] * strength);
			result[1] = skinColor[1] * (skinColor[3] * strength);
			result[2] = skinColor[2] * (skinColor[3] * strength);
			result[3] = skinColor[3] * strength;
		} else if (data <= 299) {
			return result;
		} else if (data <= 4096) {
			// Bone
			result[0] = boneColor[0] * (boneColor[3] * strength);
			result[1] = boneColor[1] * (boneColor[3] * strength);
			result[2] = boneColor[2] * (boneColor[3] * strength);
			result[3] = boneColor[3] * strength;
		}

		return result;
	}

	private void readData() throws IOException {
		// TODO: Change hardcoded var
		File file = new File("CThead");

		DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));

		// Allocate the memory - note this is fixed for this data set loop through the data reading it in
		cthead = new short[CT_z_axis][CT_y_axis][CT_x_axis];

		for (int k = 0; k < CT_z_axis; k++) {
			for (int j = 0; j < CT_y_axis; j++) {
				for (int i = 0; i < CT_x_axis; i++) {
					// Because the Endianness is wrong, it needs to be read byte at a time and swapped

					// Data is wrong Endian (check wikipedia) for Java so we need to swap the bytes around.
					// The 0xff is there because Java does not have unsigned types
					int b1 = ((int) in.readByte()) & 0xff;
					int b2 = ((int) in.readByte()) & 0xff;
					short read = (short) ((b2 << 8) | b1); // and swizzle the bytes around

					if (read < minShade) minShade = read; // update the minimum
					if (read > maxShade) maxShade = read; // update the maximum

					// Save the short that we just read
					cthead[k][j][i] = read;
				}
			}
		}

		/*
		  Diagnostic - for CThead this should be -1117, 2248.
		  (i.e. there are 3366 levels of grey (we are trying to display on 256 levels of grey),
		  therefore histogram equalization would be a good thing.
		  Maybe put your histogram equalization code here to set up the mapping array.
		 */
		System.out.println(minShade + " " + maxShade);
	}
}
