import { EventEmitter } from 'events';
import { Logger as AppLogger } from './logger';

/**
 * A sample TypeScript class used as a test fixture.
 */
export class SampleTypeScriptClass {
    private name: string;

    constructor(name: string) {
        this.name = name;
    }

    /** Returns a greeting string. */
    greet(): string {
        return `Hello, ${this.name}!`;
    }

    compute(x: number, y: number): number {
        return x + y;
    }
}

export interface Processor {
    process(input: string): string;
    count(items: string[]): number;
}

export function topLevelHelper(input: string): string {
    return input.toUpperCase();
}
