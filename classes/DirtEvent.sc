DirtEvent {

	var <dirtOrbit, <modules, <event;

	*new { |dirtOrbit, modules, event|
		^super.newCopyArgs(dirtOrbit, modules).init(event)
	}

	init { |argEvent|
		event = argEvent.parent_(dirtOrbit.defaultParentEvent);
	}

	play {
		event.use {
			~s ?? { this.splitName };
			this.getBuffer;
			this.orderRange;
			this.calcRange;
			this.playSynths;
		}
	}

	splitName {
		var s, n;
		#s, n = ~sound.asString.split($:);
		~s = s.asSymbol;
		~n = if(n.notNil) { n.asFloat } { 0.0 };
	}


	getBuffer {
		var buffer, sound, synthDesc, sustainControl;
		sound = ~s;
		~hash = ~hash ?? { sound.identityHash };
		buffer = dirtOrbit.dirt.getBuffer(sound, ~n);

		if(buffer.notNil) {
			if(buffer.sampleRate.isNil) {
				"Dirt: buffer '%' not yet completely read".format(sound).warn;
				^this
			};
			~instrument = format("dirt_sample_%_%", buffer.numChannels, ~numChannels);
			~buffer = buffer.bufnum;
			~unitDuration = buffer.duration;

		} {
			synthDesc = SynthDescLib.at(sound);
			if(synthDesc.notNil) {
				~instrument = sound;
				~note = ~n;
				~freq = (~note + 60).midicps;
				sustainControl = synthDesc.controlDict.at(\sustain);
				~unitDuration = if(sustainControl.isNil) { 1.0 } { sustainControl.defaultValue ? 1.0 }; // use definition, if defined.
			}
		}
	}

	orderRange {
		var temp;
		if(~end >= ~begin) {
			if(~speed < 0) { temp = ~end; ~end = ~begin; ~begin = temp };
		} {
			// backwards
			~speed = ~speed.neg;
		};
		~length = abs(~end - ~begin);
	}

	calcRange {

		var sustain, avgSpeed;
		var speed = ~speed;
		var accelerate = ~accelerate;
		var endSpeed;

		if (~unit == \c) { speed = speed * ~unitDuration * ~cps };

		endSpeed = speed * (1.0 + (accelerate.abs.linexp(0.01, 4, 0.001, 20, nil) * accelerate.sign));
		if(endSpeed.sign != speed.sign) { endSpeed = 0.0 }; // never turn back
		avgSpeed = speed.abs + endSpeed.abs * 0.5;

		if(~unit == \rate) { ~unit = \r }; // API adaption to tidal output


		// sustain is the duration of the sample
		switch(~unit,
			\r, {
				sustain = ~unitDuration * ~length / avgSpeed;
			},
			\c, {
				sustain = ~unitDuration * ~length / avgSpeed;
			},
			\s, {
				sustain = ~length;
			},
			{ Error("this unit ('%') is not defined".format(~unit)).throw };
		);

		if(sustain < dirtOrbit.minSustain) {
			^this // drop it.
		};

		~fadeTime = min(dirtOrbit.fadeTime, sustain * 0.19098);
		~sustain = sustain - (2 * ~fadeTime);
		~speed = speed;
		~endSpeed = endSpeed;

	}

	sendSynth { |instrument, args|
		args = args ?? { this.getMsgFunc(instrument).valueEnvir };
		~server.sendMsg(\s_new,
			instrument,
			-1, // no id
			1, // add action: addToTail
			~synthGroup, // send to group
			*args.asOSCArgArray // append all other args
		)
	}

	playMonitor {
		~server.sendMsg(\s_new,
			"dirt_monitor" ++ ~numChannels,
			-1, // no id
			3, // add action: addAfter
			~synthGroup, // send to group
			*[
				in: dirtOrbit.synthBus,  // read from private
				out: dirtOrbit.outBus,     // write to outBus,
				globalEffectBus: ~globalEffectBus,
				amp: ~amp,
				cutGroup: ~cutgroup.abs, // ignore negatives here!
				sample: ~hash, // required for the cutgroup mechanism
				sustain: ~sustain, // after sustain, free all synths and group
				fadeTime: ~fadeTime // fade in and out
			].asOSCArgArray // append all other args
		)
	}

	updateGlobalEffects {

		// these will need some refactoring

		var id, wet;
		id = dirtOrbit.globalEffects[\dirt_delay].nodeID;
		wet = 1.0 - ~dry;
		if(~delay.notNil  or: { ~delaytime > 0 } or: { ~delayfeedback > 0 }) {
			~server.sendMsg(\n_set, id,
				\amp, ~delay,
				\delaytime, ~delaytime,
				\delayfeedback, ~delayfeedback,
				\outAmp, wet
			)
		} {
			~server.sendMsg(\n_set, id, \amp, 0.0, \outAmp, wet);
		};

		id = dirtOrbit.globalEffects[\dirt_reverb].nodeID;
		if(~room.notNil) {
			~server.sendMsg(\n_set, id,
				\size, ~size,
				\amp, ~room,
				\outAmp, wet
			)
		} {
			~server.sendMsg(\n_set, id, \amp, 0.0, \outAmp, wet);
		}
	}

	prepareSynthGroup {
		~synthGroup = ~server.nextNodeID;
		~server.sendMsg(\g_new, ~synthGroup, 1, dirtOrbit.group);
	}

	playSynths {
		var diverted, server = ~server;
		var latency = ~latency + (~offset * ~speed); // ~server.latency +

		~amp = pow(~gain, 4) * dirtOrbit.amp;
		~channel !? { ~pan = ~pan + (~channel / ~numChannels) };

		server.makeBundle(latency, { // use this to build a bundle

			this.updateGlobalEffects;

			if(~cutgroup != 0) {
				server.sendMsg(\n_set, dirtOrbit.group, \gateCutGroup, ~cutgroup, \gateSample, ~hash);
			};

			this.prepareSynthGroup;
			modules.do(_.value(this));
			this.playMonitor; // this one needs to be last


		});

	}

	getMsgFunc { |instrument|
		var desc = SynthDescLib.global.at(instrument.asSymbol);
		^if(desc.notNil) { desc.msgFunc }
	}


}

