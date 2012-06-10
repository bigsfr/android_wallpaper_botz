/*
   Copyright 2012 Harri Smatt

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package fi.harism.wallpaper.asteroids;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Vector;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

public class AsteroidsRenderer implements GLSurfaceView.Renderer {

	private static final float BULLET_RADIUS = .01f;
	private static final float[] COLOR_BG = { .2f, .2f, .2f };
	private static final float[] COLOR_BORDER = { .7f, .3f, .2f };
	private static final float[] COLOR_BULLET = { .6f, .6f, .6f };
	private static final float[] COLOR_ENERGY1 = { .3f, .7f, .2f };
	private static final float[] COLOR_ENERGY2 = { .7f, .3f, .2f };
	private static final float[] COLOR_EXPLODE = { .7f, .6f, .1f };

	private static final float[] COLOR_SHIP = { .2f, .3f, .7f };
	private static final int NUM_BULLETS = 40;
	private static final int NUM_SHIPS = 40;
	private static final float SHIP_RADIUS = .1f;

	private final Vector<Bullet> mArrBullets = new Vector<Bullet>();
	private final Vector<AsteroidsParticle> mArrParticles = new Vector<AsteroidsParticle>();
	private final Vector<Ship> mArrShips = new Vector<Ship>();
	private final float[] mAspectRatio = new float[2];
	private ByteBuffer mBufferQuad;
	private FloatBuffer mBufferShipLines;
	private Context mContext;
	private final Matrix mMatrixModel = new Matrix();
	private final Matrix mMatrixModelView = new Matrix();
	private final Matrix mMatrixView = new Matrix();
	private final AsteroidsShader mShaderCircle = new AsteroidsShader();
	private final boolean[] mShaderCompilerSupport = new boolean[1];
	private final AsteroidsShader mShaderEnergy = new AsteroidsShader();
	private final AsteroidsShader mShaderLine = new AsteroidsShader();
	private final AsteroidsSolver mSolver = new AsteroidsSolver();
	private int mWidth, mHeight;

	public AsteroidsRenderer(Context context) {
		mContext = context;

		final byte[] QUAD = { -1, 1, -1, -1, 1, 1, 1, -1 };
		mBufferQuad = ByteBuffer.allocateDirect(8);
		mBufferQuad.put(QUAD).position(0);

		final float[] SHIP_LINES = { -.4f, -.5f, 0, .7f, .4f, -.5f };
		ByteBuffer buf = ByteBuffer.allocateDirect(4 * 2 * 3);
		mBufferShipLines = buf.order(ByteOrder.nativeOrder()).asFloatBuffer();
		mBufferShipLines.put(SHIP_LINES).position(0);

		for (int i = 0; i < NUM_SHIPS; ++i) {
			AsteroidsParticle p = new AsteroidsParticle();
			p.mRadius = SHIP_RADIUS;
			mArrParticles.add(p);

			Ship s = new Ship(p);
			mArrShips.add(s);
		}

		for (int i = 0; i < NUM_BULLETS; ++i) {
			mArrBullets.add(new Bullet());
		}
	}

	public void addGravity(float dx, float dy) {
		float t = Math.min(mWidth, mHeight) * .8f;
		dx /= t;
		dy /= t;

		for (AsteroidsParticle p : mArrParticles) {
			p.mVelocity[0] += dx;
			p.mVelocity[1] += dy;
		}
	}

	/**
	 * Loads String from raw resources with given id.
	 */
	private String loadRawString(int rawId) throws Exception {
		InputStream is = mContext.getResources().openRawResource(rawId);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while ((len = is.read(buf)) != -1) {
			baos.write(buf, 0, len);
		}
		return baos.toString();
	}

	@Override
	public void onDrawFrame(GL10 unused) {
		GLES20.glClearColor(COLOR_BG[0], COLOR_BG[1], COLOR_BG[2], 1f);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

		if (!mShaderCompilerSupport[0]) {
			return;
		}

		GLES20.glDisable(GLES20.GL_DEPTH_TEST);
		GLES20.glDisable(GLES20.GL_CULL_FACE);

		long time = SystemClock.uptimeMillis();
		long timeScale = time % 20000;

		float scale = 1f;
		if (timeScale > 17000) {
			float t = (timeScale - 17000) / 3000f;
			scale = 2f - t * t * (3 - 2 * t);
		} else if (timeScale > 10000) {
			scale = 2f;
		} else if (timeScale > 7000) {
			float t = (timeScale - 7000) / 3000f;
			scale = 1f + t * t * (3 - 2 * t);
		}
		// scale = .9f;

		mMatrixView.setScale(mAspectRatio[0], mAspectRatio[1]);
		mMatrixView.postScale(scale, scale);

		mSolver.animate();

		for (Bullet b : mArrBullets) {
			if (time - b.mShootTime > 700) {
				AsteroidsParticle p = mArrParticles
						.get((int) (Math.random() * mArrParticles.size()));
				while (!p.mEnabled) {
					p = mArrParticles.get((int) (Math.random() * mArrParticles
							.size()));
				}

				b.mShootTime = time;

				float len = (float) Math.sqrt(p.mVelocity[0] * p.mVelocity[0]
						+ p.mVelocity[1] * p.mVelocity[1]);
				float nx = p.mVelocity[0] / len;
				float ny = p.mVelocity[1] / len;

				b.startPos[0] = p.mPosition[0] + nx * (SHIP_RADIUS + .01f);
				b.startPos[1] = p.mPosition[1] + ny * (SHIP_RADIUS + .01f);
				b.endPos[0] = p.mPosition[0] + nx;
				b.endPos[1] = p.mPosition[1] + ny;
			}

			float t = (time - b.mShootTime) / 700f;
			AsteroidsParticle p = b.mParticle;
			p.mPosition[0] = b.startPos[0] + (b.endPos[0] - b.startPos[0]) * t;
			p.mPosition[1] = b.startPos[1] + (b.endPos[1] - b.startPos[1]) * t;
		}

		for (Ship s : mArrShips) {

			if (!s.mParticle.mEnabled) {
				continue;
			}

			for (Bullet b : mArrBullets) {
				if (mSolver.collide(s.mParticle, b.mParticle)) {
					// s.mEnergy -= .01f;
					b.mShootTime = -1;
					s.mParticle.mCollisionTime = time;
				}
			}

		}

		for (Ship ship : mArrShips) {
			if (ship.mParticle.mCollisionTime >= time) {
				ship.mEnergy -= .01f;
			}
			if (!ship.mExplode && ship.mEnergy <= 0f) {
				ship.mExplodeTime = time;
				ship.mParticle.mEnabled = false;
				ship.mExplode = true;
			}
			if (ship.mExplode && time - ship.mExplodeTime > 5000) {
				ship.mEnergy = 1.0f;
				ship.mParticle.mEnabled = true;
				ship.mExplode = false;
				ship.mVisible = true;
			}
		}

		renderBullets(mShaderCircle);
		renderShipBorders(mShaderCircle);
		renderShipEnergies(mShaderEnergy);
		renderShipLines(mShaderLine);
		renderShipExplosions(mShaderCircle);
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int width, int height) {
		mWidth = width;
		mHeight = height;

		float dx = (float) Math.max(width, height) / height;
		float dy = (float) Math.max(width, height) / width;
		mSolver.init(mArrParticles, new RectF(-dx, dy, dx, -dy));

		mAspectRatio[0] = (float) Math.min(width, height) / width;
		mAspectRatio[1] = (float) Math.min(width, height) / height;

		for (Ship ship : mArrShips) {
			ship.mEnergy = 1f;
			ship.mVisible = true;
			ship.mExplode = false;
		}
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config) {
		// Check if shader compiler is supported.
		GLES20.glGetBooleanv(GLES20.GL_SHADER_COMPILER, mShaderCompilerSupport,
				0);

		// If not, show user an error message and return immediately.
		if (mShaderCompilerSupport[0] == false) {
			String msg = mContext.getString(R.string.error_shader_compiler);
			showError(msg);
			return;
		}

		try {
			String vertexSource, fragmentSource;
			vertexSource = loadRawString(R.raw.line_vs);
			fragmentSource = loadRawString(R.raw.line_fs);
			mShaderLine.setProgram(vertexSource, fragmentSource);
			vertexSource = loadRawString(R.raw.energy_vs);
			fragmentSource = loadRawString(R.raw.energy_fs);
			mShaderEnergy.setProgram(vertexSource, fragmentSource);
			vertexSource = loadRawString(R.raw.circle_vs);
			fragmentSource = loadRawString(R.raw.circle_fs);
			mShaderCircle.setProgram(vertexSource, fragmentSource);
		} catch (Exception ex) {
			showError(ex.getMessage());
		}
	}

	private void renderBullets(AsteroidsShader shader) {
		shader.useProgram();
		int uModelViewM = shader.getHandle("uModelViewM");
		int uColor = shader.getHandle("uColor");
		int uLimits = shader.getHandle("uLimits");
		int aPosition = shader.getHandle("aPosition");

		GLES20.glUniform3fv(uColor, 1, COLOR_BULLET, 0);
		GLES20.glUniform2f(uLimits, 0, 2);

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);

		final float[] modelViewM = new float[9];

		for (Bullet b : mArrBullets) {

			AsteroidsParticle p = b.mParticle;

			mMatrixModel.setScale(BULLET_RADIUS, BULLET_RADIUS);
			mMatrixModel.postTranslate(p.mPosition[0], p.mPosition[1]);

			mMatrixModelView.set(mMatrixModel);
			mMatrixModelView.postConcat(mMatrixView);

			mMatrixModelView.getValues(modelViewM);

			GLES20.glUniformMatrix3fv(uModelViewM, 1, false, modelViewM, 0);

			GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		}
	}

	private void renderShipBorders(AsteroidsShader shader) {
		shader.useProgram();
		int uModelViewM = shader.getHandle("uModelViewM");
		int uColor = shader.getHandle("uColor");
		int uLimits = shader.getHandle("uLimits");
		int aPosition = shader.getHandle("aPosition");

		GLES20.glUniform2f(uLimits, 0.85f, 1.0f);

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);

		final float[] color = new float[3];
		final float[] modelViewM = new float[9];

		long time = SystemClock.uptimeMillis();

		for (Ship ship : mArrShips) {

			if (!ship.mVisible) {
				continue;
			}

			AsteroidsParticle p = ship.mParticle;

			float ct = (time - p.mCollisionTime) / 200f;
			if (ct < 1f) {
				mMatrixModel.setScale(SHIP_RADIUS, SHIP_RADIUS);
				mMatrixModel.postTranslate(p.mPosition[0], p.mPosition[1]);

				mMatrixModelView.set(mMatrixModel);
				mMatrixModelView.postConcat(mMatrixView);

				mMatrixModelView.getValues(modelViewM);

				GLES20.glUniformMatrix3fv(uModelViewM, 1, false, modelViewM, 0);

				for (int i = 0; i < 3; ++i) {
					color[i] = COLOR_BORDER[i]
							+ (COLOR_BG[i] - COLOR_BORDER[i]) * ct;
				}
				GLES20.glUniform3fv(uColor, 1, color, 0);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
			}
		}
	}

	private void renderShipEnergies(AsteroidsShader shader) {
		shader.useProgram();
		int uModelViewM = shader.getHandle("uModelViewM");
		int uColor1 = shader.getHandle("uColor1");
		int uColor2 = shader.getHandle("uColor2");
		int uEnergy = shader.getHandle("uEnergy");
		int aPosition = shader.getHandle("aPosition");

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);

		final float[] color1 = new float[3];
		final float[] color2 = new float[3];
		final float[] modelViewM = new float[9];

		long time = SystemClock.uptimeMillis();

		for (Ship ship : mArrShips) {

			if (!ship.mVisible) {
				continue;
			}

			AsteroidsParticle p = ship.mParticle;

			float ct = (time - p.mCollisionTime) / 400f;
			if (ct < 1f) {
				mMatrixModel.setScale(1f, .1f);
				mMatrixModel.postTranslate(0f, -.9f);

				mMatrixModel.postScale(SHIP_RADIUS, SHIP_RADIUS);
				mMatrixModel.postTranslate(p.mPosition[0], p.mPosition[1]);

				mMatrixModelView.set(mMatrixModel);
				mMatrixModelView.postConcat(mMatrixView);

				mMatrixModelView.getValues(modelViewM);

				for (int i = 0; i < 3; ++i) {
					color1[i] = COLOR_ENERGY1[i]
							+ (COLOR_BG[i] - COLOR_ENERGY1[i]) * ct;
					color2[i] = COLOR_ENERGY2[i]
							+ (COLOR_BG[i] - COLOR_ENERGY2[i]) * ct;
				}

				GLES20.glUniformMatrix3fv(uModelViewM, 1, false, modelViewM, 0);
				GLES20.glUniform1f(uEnergy, ship.mEnergy);
				GLES20.glUniform3fv(uColor1, 1, color1, 0);
				GLES20.glUniform3fv(uColor2, 1, color2, 0);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
			}
		}
	}

	private void renderShipExplosions(AsteroidsShader shader) {
		shader.useProgram();
		int uModelViewM = shader.getHandle("uModelViewM");
		int uColor = shader.getHandle("uColor");
		int uLimits = shader.getHandle("uLimits");
		int aPosition = shader.getHandle("aPosition");

		GLES20.glUniform3fv(uColor, 1, COLOR_EXPLODE, 0);

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_BYTE, false, 0,
				mBufferQuad);
		GLES20.glEnableVertexAttribArray(aPosition);

		final float[] modelViewM = new float[9];

		long time = SystemClock.uptimeMillis();

		for (Ship ship : mArrShips) {

			if (!ship.mVisible || !ship.mExplode) {
				continue;
			}

			AsteroidsParticle p = ship.mParticle;

			float ct = (time - ship.mExplodeTime) / 800f;
			if (ct < 1f) {
				mMatrixModel.setScale(SHIP_RADIUS * 1.5f, SHIP_RADIUS * 1.5f);
				mMatrixModel.postTranslate(p.mPosition[0], p.mPosition[1]);

				mMatrixModelView.set(mMatrixModel);
				mMatrixModelView.postConcat(mMatrixView);

				mMatrixModelView.getValues(modelViewM);

				GLES20.glUniformMatrix3fv(uModelViewM, 1, false, modelViewM, 0);
				GLES20.glUniform2f(uLimits, 0, ct);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
			} else {
				ship.mVisible = false;
			}
		}
	}

	private void renderShipLines(AsteroidsShader shader) {
		shader.useProgram();
		int uModelViewM = shader.getHandle("uModelViewM");
		int uColor = shader.getHandle("uColor");
		int aPosition = shader.getHandle("aPosition");

		GLES20.glUniform3fv(uColor, 1, COLOR_SHIP, 0);

		GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0,
				mBufferShipLines);
		GLES20.glEnableVertexAttribArray(aPosition);

		final float[] modelViewM = new float[9];

		float lineWidth = Math.max(1f, Math.min(mWidth, mHeight) * 0.008f);
		GLES20.glLineWidth(lineWidth);

		for (Ship ship : mArrShips) {

			if (!ship.mVisible) {
				continue;
			}

			AsteroidsParticle p = ship.mParticle;

			double tan = Math.atan2(-p.mVelocity[0], p.mVelocity[1]);
			mMatrixModel.setScale(SHIP_RADIUS, SHIP_RADIUS);
			mMatrixModel.postRotate((float) (tan * 180 / Math.PI));
			mMatrixModel.postTranslate(p.mPosition[0], p.mPosition[1]);

			mMatrixModelView.set(mMatrixModel);
			mMatrixModelView.postConcat(mMatrixView);

			mMatrixModelView.getValues(modelViewM);

			GLES20.glUniformMatrix3fv(uModelViewM, 1, false, modelViewM, 0);

			GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 3);
		}
	}

	/**
	 * Shows Toast on screen with given message.
	 */
	private void showError(final String errorMsg) {
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(mContext, errorMsg, Toast.LENGTH_LONG).show();
			}
		});
	}

	private class Bullet {
		public final float[] endPos = new float[2];
		public final AsteroidsParticle mParticle = new AsteroidsParticle();
		public long mShootTime;
		public final float[] startPos = new float[2];
	}

	private class Ship {
		public float mEnergy;
		public boolean mExplode;
		public long mExplodeTime;
		public AsteroidsParticle mParticle;
		public boolean mVisible;

		public Ship(AsteroidsParticle particle) {
			mParticle = particle;
		}
	}

}
