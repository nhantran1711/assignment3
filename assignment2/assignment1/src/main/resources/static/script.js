

let recorder;
let audio = [];

const recordButton = document.getElementById("recordButton");
const stopButton = document.getElementById("stopButton");

const status = document.getElementById("status");
const transcription = document.getElementById("transcription");

// Clicking recording button
recordButton.addEventListener("click", async () => {
	
	try {
		// Stream of audio
		const stream = await navigator.mediaDevices.getUserMedia({
			audio: true
		})
		
		recorder = new MediaRecorder(stream, {
			mimeType: "audio/webm;codecs=opus",
			audioBitsPerSecond: 32000 // low for speech should be ifne
		});
		audio = [];
		
		// if we have data from the recorder, add it into audio array
		recorder.ondataavailable = (e) => {
			if (e.data.size > 0) {
				audio.push(e.data);
			}
		}
		
		// When starting recorder, mute the record, enhanve the stop
		recorder.onstart = () => {
			status.textContent = "Recording started....";
			recordButton.disabled = true;
			recordButton.setAttribute("aria-pressed", "true")
			stopButton.disabled = false;
			stopButton.setAttribute("aria-pressed", "false");
		}
		
		// When stopping recorded, the opposite
		recorder.onstop = async () => {
			status.textContent = "Recording stopped. Waiting to transcibe....";
			recordButton.setAttribute("aria-pressed", "false");
			
			// Stop every tracks within stream
			stream.getTracks().forEach(track => track.stop());
			
			// Creat new audio blob
			const audioBlob = new Blob(audio, {
				type: recorder.mimeType
			});
			
			// Pushing new metatdata
			const formData =  new FormData();
			formData.append("audio", audioBlob, "recording.webm");
			
			try {
				const res = await fetch("/api/v1/transcribe", {
					method: "POST",
					body: formData
				})
				
				// if res shown error
				if (!res.ok) {
					throw new Error("Transcription request failed");
				}
				
				const text = await res.text();

				transcription.textContent = text;
				status.textContent = "Transcription complete.";

				
			} catch (err) {
				console.error(err);
				transcription.textContent = "Unable to transcribe audio";
				status.textContent = "Failed";
			}
			
			recordButton.disabled = false;
			stopButton.disabled = true;
		}
		
		recorder.start();
	} catch (err) {
		console.error(err);
		status.textContent = "Failed....";
	}
	


});


// Clickong on stop button
stopButton.addEventListener("click", () => {
    if (recorder && recorder.state === "recording") {
        recorder.stop();
    }
});
