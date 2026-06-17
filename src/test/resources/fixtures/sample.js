import { EventEmitter } from 'events';

/**
 * A sample JavaScript class used as a test fixture.
 */
export class SampleJavaScriptClass {
    constructor(name) {
        this.name = name;
    }

    /** Returns a greeting string. */
    greet() {
        return `Hello, ${this.name}!`;
    }

    compute(x, y) {
        return x + y;
    }
}

export function topLevelHelper(input) {
    return input.toUpperCase();
}
