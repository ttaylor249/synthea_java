package org.mitre.synthea.modules;

import java.util.ArrayList;
import java.util.List;

import org.mitre.synthea.modules.HealthRecord.Code;
import org.mitre.synthea.modules.HealthRecord.Encounter;
import org.mitre.synthea.modules.HealthRecord.EncounterType;
import org.mitre.synthea.modules.HealthRecord.Entry;
import org.mitre.synthea.modules.Transition.TransitionType;

import com.google.gson.JsonObject;

public class State {

	public enum StateType {
		INITIAL, SIMPLE, CALLSUBMODULE, TERMINAL, DELAY, GUARD,
		SETATTRIBUTE, COUNTER, ENCOUNTER, ENCOUNTEREND, 
		CONDITIONONSET, CONDITIONEND, 
		ALLERGYONSET, ALLERGYEND, 
		MEDICATIONORDER, MEDICATIONEND, 
		CAREPLANSTART, CAREPLANEND, PROCEDURE,
		VITALSIGN, OBSERVATION, OBSERVATIONGROUP, MULTIOBSERVATION,
		DIAGNOSTICREPORT, SYMPTOM, DEATH
	}
	
	public String module;
	public String name;
	public StateType type;
	public long entered;
	public long exited;
	public long next;
	private List<Transition> transitions;
	private JsonObject definition;
	
	private State() { /* empty */ }
	public State(String module, String name, JsonObject definition) {
		this.module = module;
		this.name = name;
		this.type = StateType.valueOf(definition.get("type").getAsString().toUpperCase());
		this.transitions = new ArrayList<Transition>();
		if(definition.has("direct_transition")) {
			this.transitions.add(new Transition(TransitionType.DIRECT, definition.get("direct_transition")));
		} else if(definition.has("distributed_transition")) {
			this.transitions.add(new Transition(TransitionType.DISTRIBUTED, definition.get("distributed_transition")));
		} else if(definition.has("conditional_transition")) {
			this.transitions.add(new Transition(TransitionType.CONDITIONAL, definition.get("conditional_transition")));
		} else if(definition.has("complex_transition")) {
			this.transitions.add(new Transition(TransitionType.COMPLEX, definition.get("complex_transition")));
		} else if(type != StateType.TERMINAL && type != StateType.DEATH) {
			System.err.format("State `%s` has no transition.\n", name);
		}
		this.definition = definition;
	}
	
	/**
	 * clone() should copy all the necessary variables of this State
	 * so that it can be correctly executed and modified without altering
	 * the original copy. So for example, 'entered', 'exited', and 'next' times
	 * should not be copied so the clone can be cleanly executed.
	 */
	public State clone() {
		State clone = new State();
		clone.module = this.module;
		clone.name = this.name;
		clone.type = this.type;
		clone.transitions = this.transitions;
		clone.definition = this.definition;
		return clone;
	}
	
	public String transition(Person person, long time) {
		return transitions.get(0).follow(person, time);
	}

	/**
	 * Process this State with the given Person at the specified time
	 * within the simulation.
	 * @param person : the person being simulated
	 * @param time : the date within the simulated world
	 * @return `true` if processing should continue to the next state,
	 * `false` if the processing should halt for this time step.
	 */
	public boolean process(Person person, long time) {
		System.out.format("State: %s\n", this.name);
		this.entered = time;
		switch(type) {
		case TERMINAL:
			return false;
		case INITIAL:
		case SIMPLE:
			return true;
		case CALLSUBMODULE:
			// e.g. "submodule": "medications/otc_antihistamine"
			if(this.exited == 0) {
				String submodulePath = definition.get("submodule").getAsString();
				Module submodule = Module.getModuleByPath(submodulePath);
				submodule.process(person, time);
				this.exited = time;
				return false;
			} else {
				return true;
			}
		case DELAY:
			if(this.next == 0) {
				JsonObject range = (JsonObject) definition.get("range");
				JsonObject exact = (JsonObject) definition.get("exact");
				if(range != null) {
					String units = range.get("unit").getAsString();
					double low = range.get("low").getAsDouble();
					double high = range.get("high").getAsDouble();
					this.next = time + Utilities.convertTime(units, (long) person.rand(low, high));
				} else if(exact != null) {
					String units = exact.get("unit").getAsString();
					double quantity = exact.get("quantity").getAsDouble();
					this.next = time + Utilities.convertTime(units, (long) quantity);
				} else {
					this.next = time;					
				}
			}
			if(time > this.next) {
				return true;
			} else {
				return false;
			}
		case GUARD:
			JsonObject logicDefinition = definition.get("allow").getAsJsonObject();
			Logic allow = new Logic(logicDefinition);
			return allow.test(person, time);
		case SETATTRIBUTE:
			String attribute = definition.get("attribute").getAsString();
			Object value = Utilities.primitive( definition.get("value").getAsJsonPrimitive() );
			person.attributes.put(attribute, value);
			return true;
		case COUNTER:
			attribute = definition.get("attribute").getAsString();
			int counter = 0;
			if(person.attributes.containsKey(attribute)) {
				counter = (int) person.attributes.get(attribute);
			}
			String action = definition.get("action").getAsString();
			if(action == "increment") {
				counter++;
			} else {
				counter--;
			}
			person.attributes.put(attribute, counter);
			return true;
		case SYMPTOM:
			String symptom = definition.get("symptom").getAsString();
			String cause = null;
			if(definition.has("cause")) {
				cause = definition.get("cause").getAsString();
			} else {
				cause = this.module;
			}
			JsonObject range = (JsonObject) definition.get("range");
			JsonObject exact = (JsonObject) definition.get("exact");
			if(range != null) {
				double low = range.get("low").getAsDouble();
				double high = range.get("high").getAsDouble();
				person.setSymptom(cause, symptom, (int) person.rand(low, high));
			} else if(exact != null) {
				int quantity = exact.get("quantity").getAsInt();
				person.setSymptom(cause, symptom, quantity);
			} else {
				person.setSymptom(cause, symptom, 0);					
			}
			return true;
		case ENCOUNTER:
			if(definition.has("wellness") && definition.get("wellness").getAsBoolean()) {
				Encounter encounter = person.record.currentEncounter(time);
				if(encounter.type==EncounterType.WELLNESS.toString() && encounter.stop!=0l) {
					return true;
				} else {
					// Block until we're in a wellness encounter... then proceed.
					return false;
				}
			} else {
				String encounter_class = definition.get("encounter_class").getAsString();
				Encounter encounter = person.record.encounterStart(time, encounter_class);
				encounter.name = this.name;
				if(definition.has("reason")) {
					String reason = definition.get("reason").getAsString();
					Object item = person.attributes.get(reason);
					if(item instanceof String) {
						encounter.reason = (String) item;						
					} else if(item instanceof Entry) {
						encounter.reason = ((Entry) item).type;
					}
				}
				if(definition.has("codes")) {
					definition.get("codes").getAsJsonArray().forEach(item -> {
						Code code = person.record.new Code((JsonObject) item);
						encounter.codes.add(code);
					});
				}
				return true;
			}
		case ENCOUNTEREND:
			Encounter encounter = person.record.currentEncounter(time);
			if(encounter.type != EncounterType.WELLNESS.toString()) {
				encounter.stop = time;
			}
			if(definition.has("discharge_disposition")) {
				Code code = person.record.new Code((JsonObject) definition.get("discharge_disposition"));
				encounter.discharge = code;
			}
			return true;
		case CONDITIONONSET:
			String primary_code = definition.get("codes").getAsJsonArray().get(0).getAsJsonObject().get("code").getAsString();
			Entry condition = person.record.conditionStart(time, primary_code);
			condition.name = this.name;
			if(definition.has("codes")) {
				definition.get("codes").getAsJsonArray().forEach(item -> {
					Code code = person.record.new Code((JsonObject) item);
					condition.codes.add(code);
				});
			}
			if(definition.has("assign_to_attribute")) {
				attribute = definition.get("assign_to_attribute").getAsString();
				person.attributes.put(attribute, condition);
			}
			return true;
		case CONDITIONEND:
			if(definition.has("condition_onset")) {
				String state_name = definition.get("condition_onset").getAsString();
				person.record.conditionEndByState(time, state_name);
			} else if(definition.has("referenced_by_attribute")) {
				attribute = definition.get("referenced_by_attribute").getAsString();
				condition = (Entry) person.attributes.get(attribute);
				condition.stop = time;
				person.record.conditionEnd(time, condition.type);
			} else if(definition.has("codes")) {
				definition.get("codes").getAsJsonArray().forEach(item -> {
					person.record.conditionEnd(time, item.getAsJsonObject().get("code").getAsString());
				});
			}
			return true;
		case ALLERGYONSET:
			
		default:
			System.err.format("Unhandled State Type: %s\n", type);
			return false;
		}
	}
		
	public String toString() {
		return String.format("%s '%s'", type, name);
	}
}