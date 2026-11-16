import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** shadcn-style class composition: callers can replace conflicting utilities. */
export function cn(...inputs: ClassValue[]) { return twMerge(clsx(inputs)); }
