//! Sub-pixel tracker for the island's silhouette in a screen recording.
//!
//! The island is the black shape on a lighter background. Edges come from walking in from the screen sides at
//! mid-height, and are refined with the antialiased edge pixel's coverage. Corner radius comes from how fast the
//! silhouette narrows towards the top edge.

use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::process::{Command, Stdio};

use crate::data::{median, py_round};

/// A greyscale frame, row by row.
pub struct Frame<'a> {
	pub pixels: &'a [u8],
	pub width: usize,
	pub height: usize,
}

impl Frame<'_> {
	fn row(&self, y: usize) -> &[u8] {
		&self.pixels[y * self.width..(y + 1) * self.width]
	}

	fn column(&self, x: usize) -> Vec<u8> {
		(0..self.height).map(|y| self.pixels[y * self.width + x]).collect()
	}
}

/// Walks from `start` until `run` consecutive background pixels, returning the sub-pixel island edge.
///
/// Island content may be bright, but only background forms a long bright run this close to the island.
pub fn walk_out(line: &[u8], start: i64, step: i64, bright: u8, run: i64) -> Option<f64> {
	let n = line.len() as i64;
	let at = |i: i64| f64::from(line[i as usize]);
	let mut i = start;
	let mut count = 0;
	while 0 <= i && i < n {
		count = if line[i as usize] >= bright { count + 1 } else { 0 };
		if count >= run {
			let first_bg = i - step * (run - 1);
			// The innermost pixel that may be partly island.
			let edge = first_bg - step;
			let reference = first_bg + step * 3;
			let background = if 0 <= reference && reference < n { at(reference) } else { at(first_bg) };
			let cover: f64 = [edge, first_bg].iter().map(|&p| 1.0 - (at(p) / background.max(1.0)).min(1.0)).sum();
			return Some(if step > 0 { edge as f64 + cover } else { edge as f64 + 1.0 - cover });
		}
		i += step;
	}
	None
}

/// Walks in from the screen edge to the first island pixel, returning the sub-pixel boundary.
pub fn inward(line: &[u8], step: i64, dark: u8) -> Option<f64> {
	let n = line.len() as i64;
	// The outermost pixels are the partly covered screen edge.
	let mut i = if step > 0 { 3 } else { n - 4 };
	while 0 <= i && i < n && line[i as usize] >= dark {
		i += step;
	}
	if !(0 <= i && i < n) {
		return None;
	}
	let outer = i - step;
	let reference = i - step * 4;
	let background = if 0 <= reference && reference < n { f64::from(line[reference as usize]) } else { 240.0 };
	let cover = if 0 <= outer && outer < n {
		1.0 - (f64::from(line[outer as usize]) / background.max(1.0)).min(1.0)
	} else {
		0.0
	};
	Some(if step > 0 { i as f64 - cover } else { i as f64 + 1.0 + cover })
}

pub struct Island {
	pub left: f64,
	pub right: f64,
	pub top: f64,
	pub bottom: f64,
	pub radius: f64,
}

pub struct Settings {
	pub bright: u8,
	pub run: i64,
	pub side: u8,
	pub ppp: f64,
}

pub fn measure(frame: &Frame, settings: &Settings, cx: usize) -> Option<Island> {
	let column = frame.column(cx);
	let seed = (0..frame.height).filter(|&y| y > 4).find(|&y| {
		let row = frame.row(y);
		let dark = row[cx - 30..=cx + 30].iter().filter(|&&p| p < 40).count();
		dark as f64 / 61.0 > 0.5
	})? as i64
		+ 4;
	let top = walk_out(&column, seed, -1, settings.bright, 6)?;
	// The drop shadow darkens the background below the island, so the bottom uses a lower level and a run longer
	// than any stem in the content.
	let bottom = walk_out(&column, seed, 1, 70, 26)?;
	let mid = ((top + bottom) / 2.0) as usize;
	let (left, right) = if mid as f64 / settings.ppp >= 52.0 {
		// Below the status bar the sides can be found from outside, which is immune to bright content.
		(inward(frame.row(mid), 1, 40)?, inward(frame.row(mid), -1, 40)?)
	} else {
		(
			walk_out(frame.row(mid), cx as i64, -1, settings.side, settings.run)?,
			walk_out(frame.row(mid), cx as i64, 1, settings.side, settings.run)?,
		)
	};
	let mut radii = Vec::new();
	for d in [2, 3, 4, 5, 6, 8, 10, 14] {
		let y = top as usize + d;
		if y as f64 >= bottom {
			break;
		}
		let (Some(l2), Some(r2)) = (
			walk_out(frame.row(y), cx as i64, -1, settings.side, settings.run),
			walk_out(frame.row(y), cx as i64, 1, settings.side, settings.run),
		) else {
			continue;
		};
		let inset = ((l2 - left) + (right - r2)) / 2.0;
		let dy = y as f64 + 0.5 - top;
		if inset > 0.5 {
			// The circle through (inset, dy): r = (inset² + dy²) / (2 dy).
			radii.push((inset * inset + dy * dy) / (2.0 * dy));
		}
	}
	let radius = if radii.is_empty() { 0.0 } else { median(&radii) };
	Some(Island { left, right, top, bottom, radius })
}

fn frame_times(path: &str) -> Result<Vec<f64>, String> {
	let output = Command::new("ffprobe")
		.args(["-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0", path])
		.output()
		.map_err(|error| format!("ffprobe: {error}"))?;
	if !output.status.success() {
		return Err(format!("ffprobe couldn't read {path}"));
	}
	String::from_utf8_lossy(&output.stdout)
		.lines()
		.filter(|line| !line.trim().is_empty())
		.map(|line| line.trim().trim_end_matches(',').parse().map_err(|_| format!("ffprobe said {line:?}")))
		.collect()
}

pub fn run(args: &[String]) -> Result<(), String> {
	const USAGE: &str = "usage: otori-measure track in.mp4 out.csv --crop W:H:X:Y --ppp PX_PER_POINT \
		[--bright 190] [--run 9] [--top-offset PX] [--side 185]";
	let mut positional = Vec::new();
	let (mut crop, mut ppp, mut bright, mut run, mut top_offset, mut side) = (None, 1.0, 190, 9, 0.0, 185);
	let mut iter = args.iter();
	while let Some(arg) = iter.next() {
		let mut value = || iter.next().cloned().ok_or_else(|| format!("{arg} needs a value\n{USAGE}"));
		let number = |text: String| text.parse::<f64>().map_err(|_| format!("{arg} needs a number"));
		match arg.as_str() {
			"--crop" => crop = Some(value()?),
			"--ppp" => ppp = number(value()?)?,
			"--bright" => bright = number(value()?)? as u8,
			"--run" => run = number(value()?)? as i64,
			"--top-offset" => top_offset = number(value()?)?,
			"--side" => side = number(value()?)? as u8,
			_ if arg.starts_with("--") => return Err(format!("unknown option {arg}\n{USAGE}")),
			_ => positional.push(arg.clone()),
		}
	}
	let ([input, output], Some(crop)) = (positional.as_slice(), crop) else {
		return Err(USAGE.into());
	};
	let parts: Vec<usize> =
		crop.split(':').map(|p| p.parse().map_err(|_| USAGE.to_string())).collect::<Result<_, _>>()?;
	let [w, h, x, y] = parts.as_slice() else {
		return Err(USAGE.into());
	};
	let (w, h) = (*w, *h);
	let settings = Settings { bright, run, side, ppp };
	let times = frame_times(input)?;
	let mut ffmpeg = Command::new("ffmpeg")
		.args(["-v", "error", "-nostdin", "-i", input, "-vf"])
		.arg(format!("crop={w}:{h}:{x}:{y},format=gray"))
		.args(["-fps_mode", "passthrough", "-f", "rawvideo", "-"])
		.stdout(Stdio::piped())
		.spawn()
		.map_err(|error| format!("ffmpeg: {error}"))?;
	let mut stdout = ffmpeg.stdout.take().ok_or("ffmpeg gave no output")?;
	let mut out = BufWriter::new(File::create(output).map_err(|error| format!("{output}: {error}"))?);
	let write_error = |error: std::io::Error| format!("{output}: {error}");
	writeln!(out, "frame,t,w_pt,h_pt,top_pt,cx_pt,radius_pt").map_err(write_error)?;
	let cx = w / 2;
	let t0 = times.first().copied().unwrap_or(0.0);
	let mut buffer = vec![0u8; w * h];
	let mut tracked = 0;
	for (i, t) in times.iter().enumerate() {
		if stdout.read_exact(&mut buffer).is_err() {
			break;
		}
		let frame = Frame { pixels: &buffer, width: w, height: h };
		let Some(island) = measure(&frame, &settings, cx) else {
			continue;
		};
		writeln!(
			out,
			"{i},{},{},{},{},{},{}",
			py_round(t - t0, 4),
			py_round((island.right - island.left) / ppp, 2),
			py_round((island.bottom - island.top) / ppp, 2),
			py_round((island.top - top_offset) / ppp, 2),
			py_round((island.left + island.right) / 2.0 / ppp, 2),
			py_round(island.radius / ppp, 2)
		)
		.map_err(write_error)?;
		tracked += 1;
	}
	let _ = ffmpeg.kill();
	let _ = ffmpeg.wait();
	out.flush().map_err(write_error)?;
	println!("tracked {tracked} frames -> {output}");
	Ok(())
}

#[cfg(test)]
mod tests {
	use super::*;

	// Expected values come from tools/measure/track_island.py's functions, run on the same lines.
	const LINE: [u8; 24] =
		[230, 230, 230, 230, 230, 120, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 60, 230, 230, 230, 230, 230, 230, 230];

	#[test]
	fn walk_out_finds_the_antialiased_edges() {
		assert_eq!(walk_out(&LINE, 10, -1, 190, 3), Some(5.521739130434782));
		assert_eq!(walk_out(&LINE, 10, 1, 190, 3), Some(16.73913043478261));
	}

	#[test]
	fn inward_finds_the_first_island_pixel() {
		assert_eq!(inward(&LINE, 1, 40), Some(5.521739130434782));
		assert_eq!(inward(&LINE, -1, 40), Some(16.73913043478261));
	}
}
